//! Hermes Remote desktop app. Logic lives in plain modules behind traits so
//! `cargo test` runs without a window; `run()` only wires Tauri.

pub mod bridge;
pub mod control;
pub mod desktop_entry;
pub mod flyout;
pub mod linux_service;
pub mod migration;
pub mod run_mode;
pub mod setup;
pub mod tray;
pub mod updates;
pub mod version;
pub mod win_child;

#[tauri::command]
fn app_version() -> String {
    version::app_version().to_string()
}

pub fn run() {
    let args: Vec<String> = std::env::args().collect();
    #[cfg(target_os = "linux")]
    desktop_entry::detach_if_in_terminal(&args);
    if let Some(remove_data) = migration::uninstall_flags(&args) {
        let paths = bridge::BridgePaths::from_env();
        let code = match (migration::Uninstall {
            child: run_mode::app_child(),
            runner: &bridge::SystemRunner,
            paths: &paths,
            remove_data,
            procs: &migration::SysProcesses,
            own_pid: std::process::id(),
            app_exe: &std::env::current_exe().unwrap_or_default(),
        })
        .run()
        {
            Ok(()) => 0,
            Err(e) => {
                eprintln!("uninstall: {e}");
                1
            }
        };
        std::process::exit(code);
    }
    tauri::Builder::default()
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .setup(|app| {
            run_mode::on_app_start();
            #[cfg(target_os = "linux")]
            desktop_entry::on_app_start();
            #[cfg(any(target_os = "linux", windows))]
            tray::install(app)?;
            #[cfg(windows)]
            win_child::set_app_handle(app.handle().clone());
            #[cfg(not(any(target_os = "linux", windows)))]
            let _ = app;
            Ok(())
        })
        .on_window_event(|window, event| {
            #[cfg(any(target_os = "linux", windows))]
            tray::on_window_event(window, event);
            #[cfg(not(any(target_os = "linux", windows)))]
            let _ = (window, event);
        })
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .invoke_handler(tauri::generate_handler![
            app_version,
            bridge::bridge_status,
            bridge::bridge_devices,
            bridge::bridge_pair,
            bridge::bridge_revoke,
            bridge::bridge_trusted,
            bridge::bridge_trust,
            bridge::bridge_untrust,
            bridge::bridge_doctor,
            control::bridge_start,
            control::bridge_stop,
            control::get_settings,
            control::set_start_at_logon,
            control::firewall_fix,
            control::open_logs,
            control::set_terminal_shell,
            control::get_installed_list,
            linux_service::get_linger,
            linux_service::set_linger,
            run_mode::get_run_mode,
            run_mode::set_run_mode,
            setup::setup_state,
            setup::setup_run,
            updates::update_check,
            updates::update_install
        ])
        .build(tauri::generate_context!())
        .expect("error while building Hermes Remote")
        .run(|app, event| {
            // Quit stops the bridge cleanly before the process ends; a hard kill is covered by the
            // Job Object. `Exit` fires once, after the exit is decided. Preventing `ExitRequested`
            // and calling `app.exit` again would raise `ExitRequested` again, so the app never quit.
            #[cfg(windows)]
            if let tauri::RunEvent::Exit = &event {
                win_child::quit(run_mode::app_child(), &|| {});
            }
            let _ = (app, event);
        });
}
