//! Linux app window and optional tray. A tray exists only when a StatusNotifier host is on the session bus.
//! Logic sits behind small traits so `cargo test` needs no window; `install` wires it to Tauri.

use crate::bridge::{BridgeApi, BridgePaths, ProcessRunner};
use crate::control::Control;
use serde_json::{json, Value};
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

pub const MENU_OPEN: &str = "open";
pub const MENU_START: &str = "start";
pub const MENU_STOP: &str = "stop";
pub const MENU_QUIT: &str = "quit";

/// (id, label) of every tray menu item, in order.
pub const MENU_ITEMS: [(&str, &str); 4] = [
    (MENU_OPEN, "Open Hermes Remote"),
    (MENU_START, "Start bridge"),
    (MENU_STOP, "Stop bridge"),
    (MENU_QUIT, "Quit"),
];

/// Does the session bus have a StatusNotifier host? Any error counts as no.
pub fn has_status_notifier(runner: &dyn ProcessRunner) -> bool {
    let args: Vec<String> = [
        "--session",
        "--print-reply",
        "--dest=org.freedesktop.DBus",
        "/org/freedesktop/DBus",
        "org.freedesktop.DBus.NameHasOwner",
        "string:org.kde.StatusNotifierWatcher",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    match runner.run(Path::new("dbus-send"), &args) {
        Ok(out) => out.code == Some(0) && out.stdout.contains("boolean true"),
        Err(_) => false,
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum CloseAction {
    HideToTray,
    Quit,
}

pub struct TrayPolicy<'a> {
    has_status_notifier: &'a dyn Fn() -> bool,
}

impl<'a> TrayPolicy<'a> {
    pub fn new(has_status_notifier: &'a dyn Fn() -> bool) -> Self {
        TrayPolicy { has_status_notifier }
    }

    pub fn should_create_tray(&self) -> bool {
        (self.has_status_notifier)()
    }
}

pub fn close_action(has_tray: bool) -> CloseAction {
    if has_tray {
        CloseAction::HideToTray
    } else {
        CloseAction::Quit
    }
}

/// What the tray menu can do to the app.
pub trait AppHandleLike {
    fn show_main_window(&self);
    fn exit(&self);
}

/// Run the menu item `id`. Start/stop errors are returned for the caller to log.
pub fn menu_action(id: &str, app: &dyn AppHandleLike, control: &dyn Control) -> Result<(), String> {
    match id {
        MENU_OPEN => {
            app.show_main_window();
            Ok(())
        }
        MENU_START => control.start(),
        MENU_STOP => control.stop(),
        MENU_QUIT => {
            app.exit();
            Ok(())
        }
        _ => Ok(()),
    }
}

// ------------------------------------------------------------------ trust notification

pub fn should_notify(id: &str, asked: &BTreeSet<String>) -> bool {
    !asked.contains(id)
}

fn asked_file(config_dir: &Path) -> PathBuf {
    config_dir.join("desktop-asked.json")
}

pub fn load_asked(config_dir: &Path) -> BTreeSet<String> {
    std::fs::read_to_string(asked_file(config_dir))
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default()
}

fn save_asked(config_dir: &Path, asked: &BTreeSet<String>) {
    let _ = std::fs::create_dir_all(config_dir);
    let _ = std::fs::write(asked_file(config_dir), json!(asked).to_string());
}

/// Look at one bridge status answer: when the network is untrusted and not asked about yet, remember it
/// and call `notify(name)`. Returns whether it notified.
pub fn check_trust(status: &Value, config_dir: &Path, notify: &dyn Fn(&str)) -> bool {
    let net = &status["network"];
    let (Some(id), Some(false)) = (net["id"].as_str(), net["trusted"].as_bool()) else {
        return false;
    };
    let mut asked = load_asked(config_dir);
    if !should_notify(id, &asked) {
        return false;
    }
    asked.insert(id.to_string());
    save_asked(config_dir, &asked);
    notify(net["name"].as_str().unwrap_or(id));
    true
}

/// One poll: ask the bridge for its status and run `check_trust` on the answer.
pub fn poll_once(api: &dyn BridgeApi, config_dir: &Path, notify: &dyn Fn(&str)) -> bool {
    match api.status() {
        Ok(a) => check_trust(&a.data, config_dir, notify),
        Err(_) => false,
    }
}

// ------------------------------------------------------------------ Tauri wiring

/// Whether a tray was created; managed state read by the window-close handler.
pub struct HasTray(pub bool);

struct TauriApp(tauri::AppHandle);

impl AppHandleLike for TauriApp {
    fn show_main_window(&self) {
        show_main(&self.0);
    }
    fn exit(&self) {
        self.0.exit(0);
    }
}

pub fn show_main(app: &tauri::AppHandle) {
    use tauri::Manager;
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

pub fn on_window_event(window: &tauri::Window, event: &tauri::WindowEvent) {
    use tauri::Manager;
    if let tauri::WindowEvent::CloseRequested { api, .. } = event {
        let has_tray = window.try_state::<HasTray>().map(|s| s.0).unwrap_or(false);
        if close_action(has_tray) == CloseAction::HideToTray {
            api.prevent_close();
            let _ = window.hide();
        }
    }
}

/// Create the tray when a host exists, record that in managed state, start the trust-poll thread.
pub fn install(app: &tauri::App) -> Result<(), Box<dyn std::error::Error>> {
    use tauri::menu::{Menu, MenuItem};
    use tauri::tray::TrayIconBuilder;
    use tauri::Manager;

    let probe = || has_status_notifier(&crate::bridge::SystemRunner);
    let mut has_tray = false;
    if TrayPolicy::new(&probe).should_create_tray() {
        let items = MENU_ITEMS
            .iter()
            .map(|(id, label)| MenuItem::with_id(app, *id, *label, true, None::<&str>))
            .collect::<Result<Vec<_>, _>>()?;
        let refs: Vec<&dyn tauri::menu::IsMenuItem<tauri::Wry>> =
            items.iter().map(|i| i as &dyn tauri::menu::IsMenuItem<tauri::Wry>).collect();
        let menu = Menu::with_items(app, &refs)?;
        let mut builder = TrayIconBuilder::new().menu(&menu).on_menu_event(|app, event| {
            let r = menu_action(event.id().as_ref(), &TauriApp(app.clone()), &crate::control::backend());
            if let Err(e) = r {
                eprintln!("tray menu: {e}");
            }
        });
        if let Some(icon) = app.default_window_icon() {
            builder = builder.icon(icon.clone());
        }
        match builder.build(app) {
            Ok(_) => has_tray = true,
            Err(e) => eprintln!("tray: {e}"),
        }
    }
    app.manage(HasTray(has_tray));

    let handle = app.handle().clone();
    std::thread::spawn(move || loop {
        use tauri_plugin_notification::NotificationExt;
        let dir = BridgePaths::from_env().config_dir;
        poll_once(&crate::bridge::Fallback::real(), &dir, &|name| {
            let _ = handle.notification().builder().title("Trust this network?").body(name).show();
            show_main(&handle);
        });
        std::thread::sleep(std::time::Duration::from_secs(30));
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::{Answer, BridgeResult, CliOutput};
    use std::cell::RefCell;
    use std::sync::Mutex;

    struct FakeRunner(std::io::Result<CliOutput>, Mutex<Vec<Vec<String>>>);
    impl FakeRunner {
        fn says(out: &str) -> Self {
            FakeRunner(Ok(CliOutput { code: Some(0), stdout: out.into(), stderr: String::new() }), Mutex::new(vec![]))
        }
    }
    impl ProcessRunner for FakeRunner {
        fn run_with(&self, _: &Path, args: &[String], _: &[(String, String)], _: Option<&Path>) -> std::io::Result<CliOutput> {
            self.1.lock().unwrap().push(args.to_vec());
            match &self.0 {
                Ok(o) => Ok(CliOutput { code: o.code, stdout: o.stdout.clone(), stderr: String::new() }),
                Err(e) => Err(std::io::Error::new(e.kind(), "x")),
            }
        }
    }

    #[test]
    fn tray_is_created_only_when_a_status_notifier_host_exists() {
        assert!(TrayPolicy::new(&|| true).should_create_tray());
        assert!(!TrayPolicy::new(&|| false).should_create_tray());
        assert!(has_status_notifier(&FakeRunner::says("   boolean true\n")));
        assert!(!has_status_notifier(&FakeRunner::says("   boolean false\n")));
        let broken = FakeRunner(Err(std::io::Error::from(std::io::ErrorKind::NotFound)), Mutex::new(vec![]));
        assert!(!has_status_notifier(&broken));
        let r = FakeRunner::says("boolean true");
        has_status_notifier(&r);
        assert!(r.1.lock().unwrap()[0].contains(&"string:org.kde.StatusNotifierWatcher".to_string()));
    }

    #[derive(Default)]
    struct FakeApp(RefCell<Vec<&'static str>>);
    impl AppHandleLike for FakeApp {
        fn show_main_window(&self) {
            self.0.borrow_mut().push("show");
        }
        fn exit(&self) {
            self.0.borrow_mut().push("exit");
        }
    }
    #[derive(Default)]
    struct FakeControl(RefCell<Vec<&'static str>>);
    impl Control for FakeControl {
        fn start(&self) -> Result<(), String> {
            self.0.borrow_mut().push("start");
            Ok(())
        }
        fn stop(&self) -> Result<(), String> {
            self.0.borrow_mut().push("stop");
            Err("nope".into())
        }
        fn start_at_logon(&self) -> bool {
            false
        }
        fn set_start_at_logon(&self, _: bool) -> Result<(), String> {
            Ok(())
        }
        fn firewall_fix(&self) -> Result<(), String> {
            Ok(())
        }
        fn open_logs(&self) -> Result<(), String> {
            Ok(())
        }
    }

    #[test]
    fn menu_items_and_their_actions() {
        let labels: Vec<&str> = MENU_ITEMS.iter().map(|m| m.1).collect();
        assert_eq!(labels, ["Open Hermes Remote", "Start bridge", "Stop bridge", "Quit"]);
        let (app, ctl) = (FakeApp::default(), FakeControl::default());
        assert_eq!(menu_action("open", &app, &ctl), Ok(()));
        assert_eq!(menu_action("start", &app, &ctl), Ok(()));
        assert_eq!(menu_action("stop", &app, &ctl), Err("nope".into()));
        assert_eq!(menu_action("quit", &app, &ctl), Ok(()));
        assert_eq!(*app.0.borrow(), ["show", "exit"]);
        assert_eq!(*ctl.0.borrow(), ["start", "stop"]);
    }

    #[test]
    fn closing_hides_with_a_tray_and_quits_without() {
        assert_eq!(close_action(true), CloseAction::HideToTray);
        assert_eq!(close_action(false), CloseAction::Quit);
    }

    struct FakeApi(Value);
    impl BridgeApi for FakeApi {
        fn status(&self) -> BridgeResult {
            Ok(Answer { via: "http", data: self.0.clone() })
        }
        fn devices(&self) -> BridgeResult { unimplemented!() }
        fn pair(&self, _: &str, _: bool) -> BridgeResult { unimplemented!() }
        fn revoke(&self, _: &str) -> BridgeResult { unimplemented!() }
        fn trusted(&self) -> BridgeResult { unimplemented!() }
        fn trust(&self) -> BridgeResult { unimplemented!() }
        fn untrust(&self, _: &str) -> BridgeResult { unimplemented!() }
        fn doctor(&self) -> BridgeResult { unimplemented!() }
    }

    #[test]
    fn trust_notification_fires_once_per_untrusted_network_and_is_remembered() {
        let dir = tempfile::tempdir().unwrap();
        let seen = RefCell::new(Vec::new());
        let notify = |n: &str| seen.borrow_mut().push(n.to_string());
        let net = |id: &str, trusted: bool| FakeApi(json!({"network": {"id": id, "name": "Cafe", "trusted": trusted}}));

        assert!(poll_once(&net("a", false), dir.path(), &notify));
        assert!(!poll_once(&net("a", false), dir.path(), &notify), "same network is not asked twice");
        assert!(!poll_once(&net("b", true), dir.path(), &notify), "trusted network is silent");
        assert!(poll_once(&net("c", false), dir.path(), &notify));
        assert_eq!(*seen.borrow(), ["Cafe", "Cafe"]);
        // survives a restart: the ids are on disk
        assert!(!poll_once(&net("a", false), dir.path(), &notify));
        assert!(load_asked(dir.path()).contains("a"));
        assert!(!should_notify("a", &load_asked(dir.path())));
    }
}
