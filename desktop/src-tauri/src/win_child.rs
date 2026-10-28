//! Windows: the app owns the bridge. `hermes-remote-bridge supervise` is the app's child, spawned without
//! a console window and put in a Job Object with KILL_ON_JOB_CLOSE, so the bridge dies with the app even on a
//! hard kill (the OS closes the job handle when the app dies). "Start at logon" is the autostart plugin
//! (the HKCU Run key). The pure logic compiles everywhere so tests run on Linux with fakes; the Win32 and
//! plugin glue is `cfg(windows)`.

use crate::control::Control;
use crate::run_mode::ChildSupervisor;
use crate::setup::OsStep;
use std::sync::Mutex;

/// `CreateProcess` flag: no console window for the child.
pub const CREATE_NO_WINDOW: u32 = 0x0800_0000;
/// `JOBOBJECT_BASIC_LIMIT_INFORMATION.LimitFlags`: kill every process in the job when the last handle closes.
pub const KILL_ON_JOB_CLOSE: u32 = 0x2000;

/// The Win32 Job Object calls, behind a trait so tests use a fake. Handles are passed as integers.
pub trait JobApi {
    fn create_job(&self) -> Result<isize, String>;
    fn set_limit_flags(&self, job: isize, flags: u32) -> Result<(), String>;
    fn assign(&self, job: isize, process: isize) -> Result<(), String>;
}

/// One Job Object for the app's lifetime; the handle is never closed by us.
pub struct JobGuard<J: JobApi> {
    api: J,
    job: Mutex<Option<isize>>,
}

impl<J: JobApi> JobGuard<J> {
    pub fn new(api: J) -> Self {
        JobGuard { api, job: Mutex::new(None) }
    }

    /// Create the job (first call), set KILL_ON_JOB_CLOSE, and put `process` in it.
    pub fn assign(&self, process: isize) -> Result<(), String> {
        let mut job = self.job.lock().unwrap();
        let handle = match *job {
            Some(h) => h,
            None => {
                let h = self.api.create_job()?;
                self.api.set_limit_flags(h, KILL_ON_JOB_CLOSE)?;
                *job = Some(h);
                h
            }
        };
        self.api.assign(handle, process)
    }
}

// ------------------------------------------------------------------ quit

/// Quit: stop the child cleanly first, then exit (even when the stop failed).
pub fn quit(child: &dyn ChildSupervisor, exit: &dyn Fn()) {
    let _ = child.stop();
    exit();
}

// ------------------------------------------------------------------ autostart

/// "Start at logon": the autostart plugin (HKCU Run key).
pub trait Autostart {
    fn enable(&self) -> Result<(), String>;
    fn disable(&self) -> Result<(), String>;
    fn is_enabled(&self) -> bool;
}

pub struct WindowsControl<'a> {
    pub autostart: &'a dyn Autostart,
    pub child: &'a dyn ChildSupervisor,
    pub runner: &'a dyn crate::bridge::ProcessRunner,
}

impl Control for WindowsControl<'_> {
    fn start(&self) -> Result<(), String> {
        self.child.spawn()
    }
    fn stop(&self) -> Result<(), String> {
        self.child.stop()
    }
    fn start_at_logon(&self) -> bool {
        self.autostart.is_enabled()
    }
    fn set_start_at_logon(&self, on: bool) -> Result<(), String> {
        if on {
            self.autostart.enable()
        } else {
            self.autostart.disable()
        }
    }
    /// `hermes-remote-bridge firewall --yes`: the bridge shows the one UAC prompt (ticket 31).
    fn firewall_fix(&self) -> Result<(), String> {
        firewall_fix_with(self.runner, &crate::bridge::BridgePaths::from_env().exe)
    }
    fn open_logs(&self) -> Result<(), String> {
        Err(crate::control::UNSUPPORTED.into())
    }
}

/// Run the bridge CLI's firewall fix; a non-zero exit (cancelled UAC, failure) is an error.
pub fn firewall_fix_with(runner: &dyn crate::bridge::ProcessRunner, exe: &std::path::Path) -> Result<(), String> {
    let out = runner
        .run(exe, &["firewall".to_string(), "--yes".to_string()])
        .map_err(|e| format!("could not start the bridge CLI: {e}"))?;
    if out.code == Some(0) {
        Ok(())
    } else {
        Err(format!("firewall fix not applied (cancelled or failed): {}", out.stdout.trim()))
    }
}

/// Setup's OS step on Windows: turn autostart on and start the child.
pub struct WindowsOsStep<'a> {
    pub autostart: &'a dyn Autostart,
    pub child: &'a dyn ChildSupervisor,
    /// Migrate a script install; `migration::migrate_script_install` in the real app.
    pub migrate: &'a dyn Fn(),
}

impl OsStep for WindowsOsStep<'_> {
    fn describe(&self) -> Vec<String> {
        vec!["Start at logon: HKCU Run key 'Hermes Remote'".into()]
    }
    fn apply(&self) -> Result<(), String> {
        // Old script install (tray, run-bridge, Scheduled Task) goes first; data dirs stay as they are.
        (self.migrate)();
        self.autostart.enable()?;
        self.child.spawn()
    }
    fn stop_bridge(&self) -> Result<(), String> {
        self.child.stop()
    }
    fn start_bridge(&self) -> Result<(), String> {
        self.child.spawn()
    }
}

// ------------------------------------------------------------------ Windows glue (compile-checked on Windows only)

#[cfg(windows)]
mod real {
    use super::*;
    use crate::run_mode::{Proc, Spawner};
    use std::path::Path;
    use std::sync::OnceLock;

    struct Win32Jobs;

    impl JobApi for Win32Jobs {
        fn create_job(&self) -> Result<isize, String> {
            use windows_sys::Win32::System::JobObjects::CreateJobObjectW;
            // SAFETY: null attributes and name are allowed.
            let h = unsafe { CreateJobObjectW(std::ptr::null(), std::ptr::null()) };
            if h.is_null() {
                Err(format!("CreateJobObjectW failed: {}", std::io::Error::last_os_error()))
            } else {
                Ok(h as isize)
            }
        }
        fn set_limit_flags(&self, job: isize, flags: u32) -> Result<(), String> {
            use windows_sys::Win32::System::JobObjects::{
                JobObjectExtendedLimitInformation, SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
            };
            // SAFETY: a zeroed limit struct is valid; the pointer and size describe it.
            let ok = unsafe {
                let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
                info.BasicLimitInformation.LimitFlags = flags;
                SetInformationJobObject(
                    job as _,
                    JobObjectExtendedLimitInformation,
                    &info as *const _ as *const std::ffi::c_void,
                    std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
                )
            };
            if ok == 0 {
                Err(format!("SetInformationJobObject failed: {}", std::io::Error::last_os_error()))
            } else {
                Ok(())
            }
        }
        fn assign(&self, job: isize, process: isize) -> Result<(), String> {
            use windows_sys::Win32::System::JobObjects::AssignProcessToJobObject;
            // SAFETY: both handles are live.
            if unsafe { AssignProcessToJobObject(job as _, process as _) } == 0 {
                Err(format!("AssignProcessToJobObject failed: {}", std::io::Error::last_os_error()))
            } else {
                Ok(())
            }
        }
    }

    fn jobs() -> &'static JobGuard<Win32Jobs> {
        static JOBS: OnceLock<JobGuard<Win32Jobs>> = OnceLock::new();
        JOBS.get_or_init(|| JobGuard::new(Win32Jobs))
    }

    struct OsProc(std::process::Child);

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

    pub struct OsSpawner;

    impl Spawner for OsSpawner {
        /// `pdeathsig` is a Linux idea; here the Job Object does that job.
        fn spawn(&self, exe: &Path, args: &[&str], _pdeathsig: bool) -> std::io::Result<Box<dyn Proc>> {
            use std::os::windows::io::AsRawHandle;
            use std::os::windows::process::CommandExt;
            use std::process::{Command, Stdio};
            let mut child = Command::new(exe)
                .args(args)
                .creation_flags(CREATE_NO_WINDOW)
                .stdin(Stdio::piped())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()?;
            if let Err(e) = jobs().assign(child.as_raw_handle() as isize) {
                let _ = child.kill();
                return Err(std::io::Error::new(std::io::ErrorKind::Other, e));
            }
            Ok(Box::new(OsProc(child)))
        }
    }

    fn handle() -> &'static OnceLock<tauri::AppHandle> {
        static HANDLE: OnceLock<tauri::AppHandle> = OnceLock::new();
        &HANDLE
    }

    /// Called once from `.setup`; `backend()` has no AppHandle of its own.
    pub fn set_app_handle(app: tauri::AppHandle) {
        let _ = handle().set(app);
    }

    pub struct TauriAutostart;

    impl Autostart for TauriAutostart {
        fn enable(&self) -> Result<(), String> {
            use tauri_plugin_autostart::ManagerExt;
            let app = handle().get().ok_or("the app is not ready")?;
            app.autolaunch().enable().map_err(|e| e.to_string())
        }
        fn disable(&self) -> Result<(), String> {
            use tauri_plugin_autostart::ManagerExt;
            let app = handle().get().ok_or("the app is not ready")?;
            app.autolaunch().disable().map_err(|e| e.to_string())
        }
        fn is_enabled(&self) -> bool {
            use tauri_plugin_autostart::ManagerExt;
            handle().get().map(|app| app.autolaunch().is_enabled().unwrap_or(false)).unwrap_or(false)
        }
    }
}

#[cfg(windows)]
pub use real::{set_app_handle, OsSpawner, TauriAutostart};

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    type Log = Arc<Mutex<Vec<String>>>;

    struct FakeJobs(Log);
    impl JobApi for FakeJobs {
        fn create_job(&self) -> Result<isize, String> {
            self.0.lock().unwrap().push("create".into());
            Ok(7)
        }
        fn set_limit_flags(&self, job: isize, flags: u32) -> Result<(), String> {
            self.0.lock().unwrap().push(format!("limit job={job} flags={flags:#x}"));
            Ok(())
        }
        fn assign(&self, job: isize, process: isize) -> Result<(), String> {
            self.0.lock().unwrap().push(format!("assign job={job} process={process}"));
            Ok(())
        }
    }

    #[test]
    fn the_job_is_created_limited_to_kill_on_close_then_the_child_is_assigned() {
        let log: Log = Arc::default();
        let guard = JobGuard::new(FakeJobs(log.clone()));
        guard.assign(42).unwrap();
        guard.assign(43).unwrap();
        assert_eq!(
            *log.lock().unwrap(),
            ["create", "limit job=7 flags=0x2000", "assign job=7 process=42", "assign job=7 process=43"]
        );
        assert_eq!(CREATE_NO_WINDOW, 0x0800_0000);
    }

    struct FakeChild(Log);
    impl ChildSupervisor for FakeChild {
        fn spawn(&self) -> Result<(), String> {
            self.0.lock().unwrap().push("child.spawn".into());
            Ok(())
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
    fn quit_stops_the_child_before_exiting() {
        let log: Log = Arc::default();
        let child = FakeChild(log.clone());
        quit(&child, &|| log.lock().unwrap().push("exit".into()));
        assert_eq!(*log.lock().unwrap(), ["child.stop", "exit"]);
    }

    struct FakeAutostart(Log);
    impl Autostart for FakeAutostart {
        fn enable(&self) -> Result<(), String> {
            self.0.lock().unwrap().push("autostart.enable".into());
            Ok(())
        }
        fn disable(&self) -> Result<(), String> {
            self.0.lock().unwrap().push("autostart.disable".into());
            Ok(())
        }
        fn is_enabled(&self) -> bool {
            true
        }
    }

    #[test]
    fn start_at_logon_on_and_off_map_to_the_autostart_plugin() {
        let log: Log = Arc::default();
        let (auto, child) = (FakeAutostart(log.clone()), FakeChild(log.clone()));
        let c = WindowsControl { autostart: &auto, child: &child, runner: &crate::bridge::SystemRunner };
        c.set_start_at_logon(true).unwrap();
        c.set_start_at_logon(false).unwrap();
        assert!(c.start_at_logon());
        assert_eq!(*log.lock().unwrap(), ["autostart.enable", "autostart.disable"]);
        log.lock().unwrap().clear();
        c.start().unwrap();
        c.stop().unwrap();
        assert_eq!(*log.lock().unwrap(), ["child.spawn", "child.stop"]);
        let migrate = || log.lock().unwrap().push("migrate".into());
        let step = WindowsOsStep { autostart: &auto, child: &child, migrate: &migrate };
        log.lock().unwrap().clear();
        step.apply().unwrap();
        assert_eq!(*log.lock().unwrap(), ["migrate", "autostart.enable", "child.spawn"]);
        assert_eq!(step.describe(), ["Start at logon: HKCU Run key 'Hermes Remote'"]);
    }

    struct FirewallRunner(Log, Option<i32>);
    impl crate::bridge::ProcessRunner for FirewallRunner {
        fn run_with(
            &self,
            exe: &std::path::Path,
            args: &[String],
            _env: &[(String, String)],
            _cwd: Option<&std::path::Path>,
        ) -> std::io::Result<crate::bridge::CliOutput> {
            self.0.lock().unwrap().push(format!("{} {}", exe.display(), args.join(" ")));
            Ok(crate::bridge::CliOutput { code: self.1, stdout: "Not applied".into(), stderr: String::new() })
        }
    }

    #[test]
    fn firewall_fix_runs_the_bridge_cli_and_reports_a_cancel() {
        let log: Log = Default::default();
        let exe = std::path::Path::new("bridge.exe");
        firewall_fix_with(&FirewallRunner(log.clone(), Some(0)), exe).unwrap();
        assert_eq!(*log.lock().unwrap(), ["bridge.exe firewall --yes"]);
        let err = firewall_fix_with(&FirewallRunner(log.clone(), Some(1)), exe).unwrap_err();
        assert!(err.contains("cancelled or failed"), "{err}");
    }
}
