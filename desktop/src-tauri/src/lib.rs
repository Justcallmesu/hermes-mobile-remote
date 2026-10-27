//! Hermes Remote desktop app. Logic lives in plain modules behind traits so
//! `cargo test` runs without a window; `run()` only wires Tauri.

pub mod bridge;
pub mod version;

#[tauri::command]
fn app_version() -> String {
    version::app_version().to_string()
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
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
            bridge::bridge_doctor
        ])
        .run(tauri::generate_context!())
        .expect("error while running Hermes Remote");
}
