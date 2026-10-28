//! Run mode on Linux: "always on" (the systemd user service owns the bridge) or "only while the app is
//! open" (app-only mode: `hermes-remote-bridge supervise` is the app's own child, started with
//! PDEATHSIG so a crashed app takes the bridge with it). The old owner is always stopped, and confirmed
//! stopped, before the new one starts. Nothing here runs the real systemctl or bridge in tests.

use crate::bridge::{config_value, BridgePaths};
use crate::control::Control;
use crate::linux_service::ServiceControl;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// `supervise` exits with this code when the port is already taken.
pub const PORT_CONFLICT_CODE: i32 = 76;
pub const PORT_CONFLICT: &str =
    "port conflict: another program is already using the bridge port, so the bridge did not start";
const CONFIG_FILE: &str = "desktop.toml";

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum RunMode {
    AlwaysOn,
    AppOnly,
}

impl RunMode {
    pub fn as_str(self) -> &'static str {
        match self {
            RunMode::AlwaysOn => "always-on",
            RunMode::AppOnly => "app-only",
        }
    }
    pub fn parse(s: &str) -> Option<RunMode> {
        match s {
            "always-on" => Some(RunMode::AlwaysOn),
            "app-only" => Some(RunMode::AppOnly),
            _ => None,
        }
    }
}

/// The saved mode in `<config_dir>/desktop.toml`; `AlwaysOn` when missing.
pub fn read_mode(config_dir: &Path) -> RunMode {
    std::fs::read_to_string(config_dir.join(CONFIG_FILE))
        .ok()
        .and_then(|t| RunMode::parse(&config_value(&t, "run_mode")?))
        .unwrap_or(RunMode::AlwaysOn)
}

pub fn write_mode(config_dir: &Path, mode: RunMode) -> Result<(), String> {
    std::fs::create_dir_all(config_dir).map_err(|e| e.to_string())?;
    std::fs::write(config_dir.join(CONFIG_FILE), format!("run_mode = \"{}\"\n", mode.as_str())).map_err(|e| e.to_string())
}

/// The app's one child bridge process.
pub trait ChildSupervisor {
    fn spawn(&self) -> Result<(), String>;
    /// Close stdin, wait for the exit (kill after the grace period). Ok means the child is gone.
    fn stop(&self) -> Result<(), String>;
    /// The exit code once the child has exited.
    fn exit_code(&self) -> Option<i32>;
}

/// Switches the owner of the bridge. Stop the old owner, confirm, then start the new one.
pub struct RunModeSwitcher<'a> {
    pub service: &'a dyn ServiceControl,
    pub child: &'a dyn ChildSupervisor,
    pub config_dir: &'a Path,
}

impl RunModeSwitcher<'_> {
    pub fn set(&self, mode: RunMode) -> Result<(), String> {
        match mode {
            RunMode::AppOnly => {
                // Any failure before the child is up puts the service back as it was.
                let handover = (|| {
                    self.service.stop()?;
                    self.service.disable()?;
                    if self.service.status()? {
                        return Err("the systemd service is still running; not starting a second bridge".into());
                    }
                    Ok(())
                })();
                if let Err(err) = handover {
                    return Err(self.restore_always_on(err));
                }
                let failure = match self.child.spawn() {
                    Err(e) => Some(e),
                    Ok(()) => self.child.exit_code().map(|code| {
                        if code == PORT_CONFLICT_CODE {
                            PORT_CONFLICT.to_string()
                        } else {
                            format!("the bridge exited early with code {code}")
                        }
                    }),
                };
                if let Some(err) = failure {
                    let _ = self.child.stop();
                    return Err(self.restore_always_on(err));
                }
            }
            RunMode::AlwaysOn => {
                self.child.stop()?;
                self.service.enable_now()?;
            }
        }
        write_mode(self.config_dir, mode)
    }

    /// Put the systemd service back (enabled and running); the saved mode stays always-on.
    /// A failed restore is added to the error so the user sees it.
    fn restore_always_on(&self, err: String) -> String {
        let _ = write_mode(self.config_dir, RunMode::AlwaysOn);
        match self.service.enable_now() {
            Ok(()) => err,
            Err(e) => format!("{err}; restarting the systemd service also failed: {e}"),
        }
    }
}

// ------------------------------------------------------------------ the real child

/// A running process, as much as `ProcessSupervisor` needs.
pub trait Proc: Send {
    fn close_stdin(&mut self);
    /// The exit code when exited (-1 when killed by a signal).
    fn try_wait(&mut self) -> Option<i32>;
    fn kill(&mut self);
}

pub trait Spawner {
    /// Start `exe args` with stdin piped and stdout/stderr discarded; `pdeathsig` asks the kernel to
    /// SIGTERM the child when this process dies.
    fn spawn(&self, exe: &Path, args: &[&str], pdeathsig: bool) -> std::io::Result<Box<dyn Proc>>;
}

#[cfg(unix)]
pub struct OsSpawner;

#[cfg(unix)]
struct OsProc(std::process::Child);

#[cfg(unix)]
impl Proc for OsProc {
    fn close_stdin(&mut self) {
        self.0.stdin.take();
    }
    fn try_wait(&mut self) -> Option<i32> {
        self.0.try_wait().ok().flatten().map(|s| s.code().unwrap_or(-1))
    }
    fn kill(&mut self) {
        let _ = self.0.kill();
    }
}

#[cfg(unix)]
impl Spawner for OsSpawner {
    fn spawn(&self, exe: &Path, args: &[&str], pdeathsig: bool) -> std::io::Result<Box<dyn Proc>> {
        use std::os::unix::process::CommandExt;
        use std::process::{Command, Stdio};
        let mut cmd = Command::new(exe);
        crate::bridge::scrub_appimage_env(&mut cmd, crate::bridge::in_appimage());
        cmd.args(args).stdin(Stdio::piped()).stdout(Stdio::null()).stderr(Stdio::null());
        if pdeathsig {
            // SAFETY: prctl is async-signal-safe and touches no memory of ours.
            unsafe {
                cmd.pre_exec(|| {
                    libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM as libc::c_ulong);
                    Ok(())
                });
            }
        }
        Ok(Box::new(OsProc(cmd.spawn()?)))
    }
}

#[derive(Default)]
struct State {
    child: Option<Box<dyn Proc>>,
    exited: Option<i32>,
}

pub struct ProcessSupervisor<S: Spawner> {
    pub exe: PathBuf,
    pub spawner: S,
    /// How long `spawn` watches for an immediate exit (port conflict).
    pub settle: Duration,
    /// How long `stop` waits after closing stdin before it kills.
    pub grace: Duration,
    state: Mutex<State>,
}

impl<S: Spawner> ProcessSupervisor<S> {
    pub fn new(exe: PathBuf, spawner: S, settle: Duration, grace: Duration) -> Self {
        ProcessSupervisor { exe, spawner, settle, grace, state: Mutex::new(State::default()) }
    }

    /// Poll until the child exits or `wait` passes; true when it exited.
    fn wait_exit(&self, wait: Duration) -> bool {
        let deadline = Instant::now() + wait;
        loop {
            if self.exit_code().is_some() {
                return true;
            }
            if Instant::now() >= deadline {
                return false;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
    }
}

impl<S: Spawner> ChildSupervisor for ProcessSupervisor<S> {
    fn spawn(&self) -> Result<(), String> {
        if self.state.lock().unwrap().child.is_some() && self.exit_code().is_none() {
            return Ok(());
        }
        let proc = self
            .spawner
            .spawn(&self.exe, &["supervise"], true)
            .map_err(|e| format!("could not start the bridge: {e}"))?;
        *self.state.lock().unwrap() = State { child: Some(proc), exited: None };
        self.wait_exit(self.settle);
        Ok(())
    }

    fn stop(&self) -> Result<(), String> {
        let mut st = self.state.lock().unwrap();
        let Some(child) = st.child.as_mut() else { return Ok(()) };
        child.close_stdin();
        drop(st);
        if !self.wait_exit(self.grace) {
            if let Some(c) = self.state.lock().unwrap().child.as_mut() {
                c.kill();
            }
            if !self.wait_exit(Duration::from_secs(2)) {
                return Err("the bridge did not stop".into());
            }
        }
        self.state.lock().unwrap().child = None;
        Ok(())
    }

    fn exit_code(&self) -> Option<i32> {
        let mut st = self.state.lock().unwrap();
        if let Some(c) = st.child.as_mut() {
            if let Some(code) = c.try_wait() {
                st.exited = Some(code);
            }
        }
        st.exited
    }
}

// ------------------------------------------------------------------ app wiring (Linux)

/// App-only mode: start/stop act on the child, start at login does not apply.
pub struct ModeControl<'a, C: Control> {
    pub inner: C,
    pub mode: RunMode,
    pub child: &'a dyn ChildSupervisor,
}

impl<C: Control> Control for ModeControl<'_, C> {
    fn start(&self) -> Result<(), String> {
        if self.mode == RunMode::AppOnly {
            self.child.spawn()
        } else {
            self.inner.start()
        }
    }
    fn stop(&self) -> Result<(), String> {
        if self.mode == RunMode::AppOnly {
            self.child.stop()
        } else {
            self.inner.stop()
        }
    }
    fn start_at_logon(&self) -> bool {
        self.mode == RunMode::AlwaysOn && self.inner.start_at_logon()
    }
    fn set_start_at_logon(&self, on: bool) -> Result<(), String> {
        if self.mode == RunMode::AppOnly {
            return Err("not available in app-only mode".into());
        }
        self.inner.set_start_at_logon(on)
    }
    fn firewall_fix(&self) -> Result<(), String> {
        self.inner.firewall_fix()
    }
    fn open_logs(&self) -> Result<(), String> {
        self.inner.open_logs()
    }
}

#[cfg(windows)]
pub use crate::win_child::OsSpawner;

#[cfg(any(target_os = "linux", windows))]
pub fn app_child() -> &'static ProcessSupervisor<OsSpawner> {
    static CHILD: std::sync::OnceLock<ProcessSupervisor<OsSpawner>> = std::sync::OnceLock::new();
    CHILD.get_or_init(|| {
        ProcessSupervisor::new(
            BridgePaths::from_env().exe,
            OsSpawner,
            Duration::from_millis(1500),
            Duration::from_secs(10),
        )
    })
}

/// On app start in app-only mode, the app starts its own bridge.
#[cfg(target_os = "linux")]
pub fn on_app_start() {
    if read_mode(&BridgePaths::from_env().config_dir) == RunMode::AppOnly {
        let _ = app_child().spawn();
    }
}

/// On Windows there is no run mode: the app always owns the bridge.
#[cfg(windows)]
pub fn on_app_start() {
    let _ = app_child().spawn();
}

#[cfg(not(any(target_os = "linux", windows)))]
pub fn on_app_start() {}

#[tauri::command]
pub async fn get_run_mode() -> Result<String, String> {
    Ok(read_mode(&BridgePaths::from_env().config_dir).as_str().into())
}

#[cfg(target_os = "linux")]
#[tauri::command]
pub async fn set_run_mode(mode: String) -> Result<(), String> {
    let mode = RunMode::parse(&mode).ok_or("unknown run mode")?;
    let service = crate::linux_service::Systemd::from_env();
    RunModeSwitcher { service: &service, child: app_child(), config_dir: &BridgePaths::from_env().config_dir }
        .set(mode)
}

#[cfg(not(target_os = "linux"))]
#[tauri::command]
pub async fn set_run_mode(_mode: String) -> Result<(), String> {
    Err(crate::control::UNSUPPORTED.into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    type Log = Arc<Mutex<Vec<String>>>;

    struct FakeService {
        log: Log,
        active: bool,
    }
    impl ServiceControl for FakeService {
        fn status(&self) -> Result<bool, String> {
            self.log.lock().unwrap().push("service.status".into());
            Ok(self.active)
        }
        fn start(&self) -> Result<(), String> {
            self.log.lock().unwrap().push("service.start".into());
            Ok(())
        }
        fn stop(&self) -> Result<(), String> {
            self.log.lock().unwrap().push("service.stop".into());
            Ok(())
        }
        fn enable(&self) -> Result<(), String> {
            Ok(())
        }
        fn disable(&self) -> Result<(), String> {
            self.log.lock().unwrap().push("service.disable".into());
            Ok(())
        }
        fn enable_now(&self) -> Result<(), String> {
            self.log.lock().unwrap().push("service.enable_now".into());
            Ok(())
        }
        fn is_enabled(&self) -> bool {
            false
        }
        fn linger_on(&self) -> Result<(), String> {
            Ok(())
        }
        fn linger_off(&self) -> Result<(), String> {
            Ok(())
        }
        fn linger(&self) -> bool {
            false
        }
        fn write_unit(&self, _: &Path) -> Result<(), String> {
            Ok(())
        }
    }

    struct FakeChild {
        log: Log,
        code: Option<i32>,
    }
    impl ChildSupervisor for FakeChild {
        fn spawn(&self) -> Result<(), String> {
            self.log.lock().unwrap().push("child.spawn".into());
            Ok(())
        }
        fn stop(&self) -> Result<(), String> {
            self.log.lock().unwrap().push("child.stop".into());
            Ok(())
        }
        fn exit_code(&self) -> Option<i32> {
            self.code
        }
    }

    fn switch(mode: RunMode, code: Option<i32>) -> (Result<(), String>, Vec<String>, tempfile::TempDir) {
        let log: Log = Arc::default();
        let dir = tempfile::tempdir().unwrap();
        let service = FakeService { log: log.clone(), active: false };
        let child = FakeChild { log: log.clone(), code };
        let r = RunModeSwitcher { service: &service, child: &child, config_dir: dir.path() }.set(mode);
        let calls = log.lock().unwrap().clone();
        (r, calls, dir)
    }

    #[test]
    fn the_old_owner_is_stopped_and_confirmed_before_the_new_one_starts() {
        let (r, calls, dir) = switch(RunMode::AppOnly, None);
        r.unwrap();
        assert_eq!(calls, ["service.stop", "service.disable", "service.status", "child.spawn"]);
        assert_eq!(read_mode(dir.path()), RunMode::AppOnly);
        let (r, calls, dir) = switch(RunMode::AlwaysOn, None);
        r.unwrap();
        assert_eq!(calls, ["child.stop", "service.enable_now"]);
        assert_eq!(read_mode(dir.path()), RunMode::AlwaysOn);
        assert_eq!(read_mode(tempfile::tempdir().unwrap().path()), RunMode::AlwaysOn);
    }

    #[test]
    fn a_service_still_running_after_stop_is_restored_and_no_child_starts() {
        let log: Log = Default::default();
        let service = FakeService { log: log.clone(), active: true };
        let child = FakeChild { log: log.clone(), code: None };
        let dir = tempfile::tempdir().unwrap();
        let sw = RunModeSwitcher { service: &service, child: &child, config_dir: dir.path() };
        let err = sw.set(RunMode::AppOnly).unwrap_err();
        assert!(err.contains("still running"), "{err}");
        let calls = log.lock().unwrap().clone();
        assert!(!calls.contains(&"child.spawn".to_string()), "{calls:?}");
        assert_eq!(calls.last().unwrap(), "service.enable_now");
        assert_eq!(read_mode(dir.path()), RunMode::AlwaysOn);
    }

    #[test]
    fn a_port_conflict_restores_the_service_and_keeps_always_on() {
        let (r, calls, dir) = switch(RunMode::AppOnly, Some(PORT_CONFLICT_CODE));
        assert_eq!(r.unwrap_err(), PORT_CONFLICT);
        assert_eq!(calls[calls.len() - 2..], ["child.stop", "service.enable_now"]);
        assert_eq!(read_mode(dir.path()), RunMode::AlwaysOn);
    }

    struct SpawnFails(Log);
    impl ChildSupervisor for SpawnFails {
        fn spawn(&self) -> Result<(), String> {
            Err("could not start the bridge: boom".into())
        }
        fn stop(&self) -> Result<(), String> {
            self.0.lock().unwrap().push("child.stop".into());
            Ok(())
        }
        fn exit_code(&self) -> Option<i32> {
            None
        }
    }

    #[test]
    fn another_early_exit_or_a_spawn_error_also_restores_the_service() {
        let (r, calls, dir) = switch(RunMode::AppOnly, Some(1));
        let err = r.unwrap_err();
        assert!(err.contains("exited") && err.contains('1') && err != PORT_CONFLICT, "{err}");
        assert_eq!(calls[calls.len() - 2..], ["child.stop", "service.enable_now"]);
        assert_eq!(read_mode(dir.path()), RunMode::AlwaysOn);

        let log: Log = Arc::default();
        let dir = tempfile::tempdir().unwrap();
        let service = FakeService { log: log.clone(), active: false };
        let child = SpawnFails(log.clone());
        let err = RunModeSwitcher { service: &service, child: &child, config_dir: dir.path() }.set(RunMode::AppOnly).unwrap_err();
        assert!(err.contains("boom"), "{err}");
        let calls = log.lock().unwrap().clone();
        assert_eq!(calls[calls.len() - 2..], ["child.stop", "service.enable_now"]);
        assert_eq!(read_mode(dir.path()), RunMode::AlwaysOn);
    }

    struct FakeProc;
    impl Proc for FakeProc {
        fn close_stdin(&mut self) {}
        fn try_wait(&mut self) -> Option<i32> {
            None
        }
        fn kill(&mut self) {}
    }
    struct FakeSpawner(Log);
    impl Spawner for FakeSpawner {
        fn spawn(&self, exe: &Path, args: &[&str], pdeathsig: bool) -> std::io::Result<Box<dyn Proc>> {
            self.0.lock().unwrap().push(format!("{} {} pdeathsig={pdeathsig}", exe.display(), args.join(" ")));
            Ok(Box::new(FakeProc))
        }
    }

    #[test]
    fn the_child_is_spawned_with_pdeathsig() {
        let log: Log = Arc::default();
        let sup = ProcessSupervisor::new("/v/bin/hermes-remote-bridge".into(), FakeSpawner(log.clone()), Duration::ZERO, Duration::ZERO);
        sup.spawn().unwrap();
        assert_eq!(*log.lock().unwrap(), ["/v/bin/hermes-remote-bridge supervise pdeathsig=true"]);
    }

    #[test]
    fn a_second_spawn_with_a_live_child_returns_ok_without_a_second_bridge() {
        let log: Log = Arc::default();
        let sup = Arc::new(ProcessSupervisor::new("x".into(), FakeSpawner(log.clone()), Duration::ZERO, Duration::ZERO));
        let (tx, rx) = std::sync::mpsc::channel();
        let s = sup.clone();
        std::thread::spawn(move || {
            let _ = tx.send((s.spawn(), s.spawn()));
        });
        let (a, b) = rx.recv_timeout(Duration::from_secs(5)).expect("second spawn hung");
        assert!(a.is_ok() && b.is_ok());
        assert_eq!(log.lock().unwrap().len(), 1);
    }

    struct FakeInner(Log);
    impl Control for FakeInner {
        fn start(&self) -> Result<(), String> {
            self.0.lock().unwrap().push("inner.start".into());
            Ok(())
        }
        fn stop(&self) -> Result<(), String> {
            self.0.lock().unwrap().push("inner.stop".into());
            Ok(())
        }
        fn start_at_logon(&self) -> bool {
            true
        }
        fn set_start_at_logon(&self, on: bool) -> Result<(), String> {
            self.0.lock().unwrap().push(format!("inner.logon={on}"));
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
    fn mode_control_routes_start_stop_and_start_at_logon_by_mode() {
        let log: Log = Arc::default();
        let child = FakeChild { log: log.clone(), code: None };
        let app = ModeControl { inner: FakeInner(log.clone()), mode: RunMode::AppOnly, child: &child };
        app.start().unwrap();
        app.stop().unwrap();
        assert!(!app.start_at_logon());
        assert!(app.set_start_at_logon(true).is_err());
        assert_eq!(*log.lock().unwrap(), ["child.spawn", "child.stop"]);
        log.lock().unwrap().clear();
        let on = ModeControl { inner: FakeInner(log.clone()), mode: RunMode::AlwaysOn, child: &child };
        on.start().unwrap();
        on.stop().unwrap();
        assert!(on.start_at_logon());
        on.set_start_at_logon(false).unwrap();
        assert_eq!(*log.lock().unwrap(), ["inner.start", "inner.stop", "inner.logon=false"]);
    }

    #[cfg(unix)]
    #[test]
    fn the_os_spawner_installs_the_hook_and_exit_76_is_reported_as_a_port_conflict() {
        struct Sh;
        impl Spawner for Sh {
            fn spawn(&self, _: &Path, _: &[&str], p: bool) -> std::io::Result<Box<dyn Proc>> {
                OsSpawner.spawn(Path::new("/bin/sh"), &["-c", "exit 76"], p)
            }
        }
        let sup = ProcessSupervisor::new("x".into(), Sh, Duration::from_secs(5), Duration::ZERO);
        sup.spawn().unwrap();
        assert_eq!(sup.exit_code(), Some(76));
        let log: Log = Arc::default();
        let dir = tempfile::tempdir().unwrap();
        let service = FakeService { log: log.clone(), active: false };
        let err = RunModeSwitcher { service: &service, child: &sup, config_dir: dir.path() }.set(RunMode::AppOnly).unwrap_err();
        assert_eq!(err, PORT_CONFLICT);
        assert_eq!(read_mode(dir.path()), RunMode::AlwaysOn);
    }
}
