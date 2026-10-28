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
pub const MENU_PAIR: &str = "pair";
pub const MENU_QUIT: &str = "quit";

/// (id, label) of every tray menu item, in order.
pub const MENU_ITEMS: [(&str, &str); 5] = [
    (MENU_OPEN, "Open Hermes Remote"),
    (MENU_START, "Start bridge"),
    (MENU_STOP, "Stop bridge"),
    (MENU_PAIR, "Pair a device"),
    (MENU_QUIT, "Quit"),
];

/// Tray icon state, from the bridge status answer.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum IconState {
    Running,
    Degraded,
    Stopped,
    Problem,
}

/// running && trusted && firewall_ok -> Running; running otherwise -> Degraded; not running -> Stopped;
/// an error answer (or no `running` field) -> Problem.
pub fn icon_state(status: &Value) -> IconState {
    if status.get("error").is_some() {
        return IconState::Problem;
    }
    match status["running"].as_bool() {
        None => IconState::Problem,
        Some(false) => IconState::Stopped,
        Some(true) => {
            let trusted = status["network"]["trusted"].as_bool().unwrap_or(true);
            let firewall_ok = status["firewall_ok"].as_bool().unwrap_or(true);
            if trusted && firewall_ok {
                IconState::Running
            } else {
                IconState::Degraded
            }
        }
    }
}

/// The tint colour (RGB) of each state.
pub fn icon_color(state: IconState) -> [u8; 3] {
    match state {
        IconState::Running => [0x1a, 0x8a, 0x3a],
        IconState::Degraded => [0xb7, 0x79, 0x1f],
        IconState::Stopped => [0x80, 0x80, 0x80],
        IconState::Problem => [0xc0, 0x39, 0x2b],
    }
}

/// Tint RGBA pixels: half the colour, half the original; alpha is kept.
pub fn tint_rgba(rgba: &[u8], color: [u8; 3]) -> Vec<u8> {
    let mut out = rgba.to_vec();
    for px in out.chunks_exact_mut(4) {
        for c in 0..3 {
            px[c] = ((px[c] as u16 + color[c] as u16) / 2) as u8;
        }
    }
    out
}

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
    /// Show the window and switch its tab to `page`.
    fn navigate(&self, page: &str);
}

/// Run the menu item `id`. Start/stop errors are returned for the caller to log.
pub fn menu_action(id: &str, app: &dyn AppHandleLike, control: &dyn Control) -> Result<(), String> {
    match id {
        MENU_PAIR => {
            app.navigate("pair");
            Ok(())
        }
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
    poll_with_icon(api, config_dir, notify, &|_| {})
}

/// `poll_once` that also reports the icon state of the same status answer.
pub fn poll_with_icon(api: &dyn BridgeApi, config_dir: &Path, notify: &dyn Fn(&str), set_icon: &dyn Fn(IconState)) -> bool {
    match api.status() {
        Ok(a) => {
            set_icon(icon_state(&a.data));
            check_trust(&a.data, config_dir, notify)
        }
        Err(_) => {
            set_icon(IconState::Problem);
            false
        }
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
    fn navigate(&self, page: &str) {
        use tauri::Manager;
        show_main(&self.0);
        if let Some(w) = self.0.get_webview_window("main") {
            // App.vue listens for this DOM event and switches tab. `page` is a fixed id, never user text.
            let _ = w.eval(format!("window.dispatchEvent(new CustomEvent('navigate',{{detail:{}}}))", json!(page)));
        }
    }
}

/// True while the main window is shown as the tray flyout (Windows only).
#[cfg(windows)]
static FLYOUT_OPEN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn show_main(app: &tauri::AppHandle) {
    use tauri::Manager;
    if let Some(w) = app.get_webview_window("main") {
        #[cfg(windows)]
        if FLYOUT_OPEN.swap(false, std::sync::atomic::Ordering::SeqCst) {
            // Back from flyout to a normal window.
            let _ = w.set_always_on_top(false);
            let _ = w.set_skip_taskbar(false);
            let _ = w.set_decorations(true);
        }
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

/// When the flyout was last hidden because it lost focus; a tray click right after is the click that closed it.
#[cfg(windows)]
static FOCUS_HIDE_AT: std::sync::Mutex<Option<std::time::Instant>> = std::sync::Mutex::new(None);
/// The tray icon's last clicked position, used when a notification opens the flyout.
#[cfg(windows)]
static LAST_TRAY_POS: std::sync::Mutex<Option<(f64, f64)>> = std::sync::Mutex::new(None);

#[cfg(windows)]
fn monitor_work(m: &tauri::monitor::Monitor) -> crate::flyout::Rect {
    let a = m.work_area();
    crate::flyout::Rect { x: a.position.x, y: a.position.y, w: a.size.width as i32, h: a.size.height as i32 }
}

/// Left click on the Windows tray icon: hide the flyout when it is open, else show it at the taskbar corner.
#[cfg(windows)]
fn toggle_flyout(app: &tauri::AppHandle, click: tauri::PhysicalPosition<f64>) {
    use tauri::Manager;
    *LAST_TRAY_POS.lock().unwrap() = Some((click.x, click.y));
    let since = FOCUS_HIDE_AT.lock().unwrap().map(|t| t.elapsed());
    if crate::flyout::ignore_tray_click(since) {
        return;
    }
    if let Some(w) = app.get_webview_window("main") {
        if FLYOUT_OPEN.load(std::sync::atomic::Ordering::SeqCst) && w.is_visible().unwrap_or(false) {
            FLYOUT_OPEN.store(false, std::sync::atomic::Ordering::SeqCst);
            let _ = w.hide();
            return;
        }
    }
    open_flyout(app, (click.x, click.y));
}

/// The "Trust this network?" notification: the flyout on Windows (at the tray icon's last position, else the
/// primary work area's corner), the normal window elsewhere.
fn show_for_trust(app: &tauri::AppHandle) {
    #[cfg(windows)]
    if crate::flyout::trust_target(true) == crate::flyout::TrustTarget::Flyout {
        let pos = LAST_TRAY_POS.lock().unwrap().unwrap_or_else(|| {
            let w = crate::flyout::work_area(None, app.primary_monitor().ok().flatten().map(|m| monitor_work(&m)));
            ((w.x + w.w) as f64, (w.y + w.h) as f64)
        });
        open_flyout(app, pos);
        return;
    }
    show_main(app);
}

/// Show the main window as the flyout near `click`.
#[cfg(windows)]
fn open_flyout(app: &tauri::AppHandle, click: (f64, f64)) {
    use crate::flyout::{place, Taskbar, WinTaskbar, FLYOUT_HEIGHT, FLYOUT_WIDTH};
    use std::sync::atomic::Ordering;
    use tauri::Manager;
    let Some(w) = app.get_webview_window("main") else { return };
    let scale = w.scale_factor().unwrap_or(1.0);
    let size = ((FLYOUT_WIDTH * scale) as i32, (FLYOUT_HEIGHT * scale) as i32);
    let at_click = app.monitor_from_point(click.0, click.1).ok().flatten().map(|m| monitor_work(&m));
    let primary = app.primary_monitor().ok().flatten().map(|m| monitor_work(&m));
    let work = crate::flyout::work_area(at_click, primary);
    let r = place(WinTaskbar.edge(), work, (click.0 as i32, click.1 as i32), size);
    let _ = w.set_decorations(false);
    let _ = w.set_always_on_top(true);
    let _ = w.set_skip_taskbar(true);
    let _ = w.set_size(tauri::LogicalSize::new(FLYOUT_WIDTH, FLYOUT_HEIGHT));
    let _ = w.set_position(tauri::PhysicalPosition::new(r.x, r.y));
    FLYOUT_OPEN.store(true, Ordering::SeqCst);
    let _ = w.show();
    let _ = w.set_focus();
}

pub fn on_window_event(window: &tauri::Window, event: &tauri::WindowEvent) {
    use tauri::Manager;
    match event {
        tauri::WindowEvent::CloseRequested { api, .. } => {
            let has_tray = window.try_state::<HasTray>().map(|s| s.0).unwrap_or(false);
            if close_action(has_tray) == CloseAction::HideToTray {
                api.prevent_close();
                let _ = window.hide();
            }
        }
        #[cfg(windows)]
        tauri::WindowEvent::Focused(false) => {
            if FLYOUT_OPEN.swap(false, std::sync::atomic::Ordering::SeqCst) {
                *FOCUS_HIDE_AT.lock().unwrap() = Some(std::time::Instant::now());
                let _ = window.hide();
            }
        }
        _ => {}
    }
}

/// Tinted copies of the default window icon, one per state.
fn state_icon(base: &tauri::image::Image<'_>, state: IconState) -> tauri::image::Image<'static> {
    tauri::image::Image::new_owned(tint_rgba(base.rgba(), icon_color(state)), base.width(), base.height())
}

/// The StatusNotifier probe on Linux; Windows always has a notification area.
fn tray_host_available() -> bool {
    #[cfg(windows)]
    {
        true
    }
    #[cfg(not(windows))]
    {
        has_status_notifier(&crate::bridge::SystemRunner)
    }
}

/// Create the tray when a host exists, record that in managed state, start the trust-poll thread.
pub fn install(app: &tauri::App) -> Result<(), Box<dyn std::error::Error>> {
    use tauri::menu::{Menu, MenuItem};
    use tauri::tray::TrayIconBuilder;
    use tauri::Manager;

    let probe = tray_host_available;
    let mut has_tray = false;
    let mut tray_icon = None;
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
        #[cfg(windows)]
        {
            use tauri::tray::{MouseButton, MouseButtonState, TrayIconEvent};
            builder = builder.show_menu_on_left_click(false).on_tray_icon_event(|tray, event| {
                if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, position, .. } = event {
                    toggle_flyout(tray.app_handle(), position);
                }
            });
        }
        if let Some(icon) = app.default_window_icon() {
            builder = builder.icon(icon.clone());
        }
        match builder.build(app) {
            Ok(t) => {
                has_tray = true;
                tray_icon = Some(t);
            }
            Err(e) => eprintln!("tray: {e}"),
        }
    }
    app.manage(HasTray(has_tray));

    let handle = app.handle().clone();
    let base_icon = app
        .default_window_icon()
        .map(|i| tauri::image::Image::new_owned(i.rgba().to_vec(), i.width(), i.height()));
    std::thread::spawn(move || loop {
        use tauri_plugin_notification::NotificationExt;
        let dir = BridgePaths::from_env().config_dir;
        let set_icon = |state: IconState| {
            if let (Some(t), Some(base)) = (&tray_icon, &base_icon) {
                let _ = t.set_icon(Some(state_icon(base, state)));
            }
        };
        poll_with_icon(&crate::bridge::Fallback::real(), &dir, &|name| {
            let _ = handle.notification().builder().title("Trust this network?").body(name).show();
            show_for_trust(&handle);
        }, &set_icon);
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
        fn navigate(&self, page: &str) {
            self.0.borrow_mut().push(if page == "pair" { "pair" } else { "other" });
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
        assert_eq!(labels, ["Open Hermes Remote", "Start bridge", "Stop bridge", "Pair a device", "Quit"]);
        let (app, ctl) = (FakeApp::default(), FakeControl::default());
        assert_eq!(menu_action("open", &app, &ctl), Ok(()));
        assert_eq!(menu_action("start", &app, &ctl), Ok(()));
        assert_eq!(menu_action("stop", &app, &ctl), Err("nope".into()));
        assert_eq!(menu_action("pair", &app, &ctl), Ok(()));
        assert_eq!(menu_action("quit", &app, &ctl), Ok(()));
        assert_eq!(*app.0.borrow(), ["show", "pair", "exit"]);
        assert_eq!(*ctl.0.borrow(), ["start", "stop"]);
    }

    #[test]
    fn icon_state_follows_the_status_answer() {
        let ok = json!({"running": true, "firewall_ok": true, "network": {"trusted": true}});
        assert_eq!(icon_state(&ok), IconState::Running);
        let untrusted = json!({"running": true, "firewall_ok": true, "network": {"trusted": false}});
        assert_eq!(icon_state(&untrusted), IconState::Degraded);
        let no_fw = json!({"running": true, "firewall_ok": false, "network": {"trusted": true}});
        assert_eq!(icon_state(&no_fw), IconState::Degraded);
        assert_eq!(icon_state(&json!({"running": false})), IconState::Stopped);
        assert_eq!(icon_state(&json!({"error": "boom"})), IconState::Problem);
        // each state gets its own tint; alpha stays
        let red = tint_rgba(&[0, 0, 0, 200], icon_color(IconState::Problem));
        assert_eq!(red, [0x60, 0x1c, 0x15, 200]);
        assert_ne!(icon_color(IconState::Running), icon_color(IconState::Stopped));
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
