//! Windows: migrate a script install (PowerShell tray + run-bridge + Scheduled Task) to the app, and the
//! `--uninstall` steps the NSIS hook calls. Logic sits behind traits so tests use fakes; data dirs
//! (config, devices, trusted networks) and Hermes' `.env` are never touched by migration.

use crate::bridge::{BridgePaths, ProcessRunner};
use crate::run_mode::ChildSupervisor;
use std::path::Path;

pub const TASK_NAME: &str = "Hermes Mobile Remote";
pub const RUN_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
pub const RUN_VALUE: &str = "Hermes Remote";

// ------------------------------------------------------------------ process match

/// Is `name` a PowerShell host (`powershell.exe` / `pwsh.exe`)?
fn is_powershell(name: &str) -> bool {
    let n = name.to_ascii_lowercase();
    matches!(n.as_str(), "powershell.exe" | "powershell" | "pwsh.exe" | "pwsh")
}

/// Does the run of text contain a path separator followed by `tray.ps1` or `run-bridge.ps1`?
fn has_script(run: &str) -> bool {
    run.char_indices()
        .filter(|(_, c)| *c == '\\' || *c == '/')
        .any(|(i, _)| {
            let rest = &run[i + 1..];
            rest.starts_with("tray.ps1") || rest.starts_with("run-bridge.ps1")
        })
}

/// install.ps1's regex `-File\s+(?:"[^"]*|\S*)[\\/](tray|run-bridge)\.ps1`, case-insensitive, by hand.
pub fn runs_old_script(cmdline: &str) -> bool {
    let s = cmdline.to_ascii_lowercase();
    let mut from = 0;
    while let Some(p) = s[from..].find("-file") {
        let after = &s[from + p + 5..];
        let rest = after.trim_start();
        if rest.len() < after.len() {
            let nonspace = rest.split_whitespace().next().unwrap_or("");
            let quoted = rest.strip_prefix('"').map(|q| q.split('"').next().unwrap_or(""));
            if has_script(nonspace) || quoted.is_some_and(has_script) {
                return true;
            }
        }
        from += p + 5;
    }
    false
}

pub struct ProcInfo {
    pub pid: u32,
    pub name: String,
    pub cmd: String,
}

pub trait ProcessList {
    fn list(&self) -> Vec<ProcInfo>;
    fn kill(&self, pid: u32);
}

/// The real list: sysinfo, with the command line of every process.
pub struct SysProcesses;

impl ProcessList for SysProcesses {
    fn list(&self) -> Vec<ProcInfo> {
        use sysinfo::{ProcessRefreshKind, ProcessesToUpdate, System, UpdateKind};
        let mut sys = System::new();
        sys.refresh_processes_specifics(
            ProcessesToUpdate::All,
            true,
            ProcessRefreshKind::nothing().with_cmd(UpdateKind::Always),
        );
        sys.processes()
            .iter()
            .map(|(pid, p)| ProcInfo {
                pid: pid.as_u32(),
                name: p.name().to_string_lossy().into_owned(),
                cmd: p.cmd().iter().map(|a| a.to_string_lossy()).collect::<Vec<_>>().join(" "),
            })
            .collect()
    }
    fn kill(&self, pid: u32) {
        use sysinfo::{Pid, ProcessesToUpdate, System};
        let mut sys = System::new();
        sys.refresh_processes(ProcessesToUpdate::Some(&[Pid::from_u32(pid)]), true);
        if let Some(p) = sys.process(Pid::from_u32(pid)) {
            p.kill();
        }
    }
}

// ------------------------------------------------------------------ Migration

pub struct Migration<'a> {
    pub procs: &'a dyn ProcessList,
    pub runner: &'a dyn ProcessRunner,
    pub own_pid: u32,
}

fn schtasks() -> &'static Path {
    Path::new("schtasks")
}

fn delete_task(runner: &dyn ProcessRunner) {
    let q = ["/Query", "/TN", TASK_NAME].map(String::from);
    // An absent task (non-zero exit) or a missing schtasks is fine.
    if matches!(runner.run(schtasks(), &q), Ok(o) if o.code == Some(0)) {
        let d = ["/Delete", "/TN", TASK_NAME, "/F"].map(String::from);
        let _ = runner.run(schtasks(), &d);
    }
}

impl Migration<'_> {
    /// Kill the old PowerShell tray / run-bridge, then delete the old Scheduled Task.
    pub fn run(&self) {
        for p in self.procs.list() {
            if p.pid != self.own_pid && is_powershell(&p.name) && runs_old_script(&p.cmd) {
                self.procs.kill(p.pid);
            }
        }
        delete_task(self.runner);
    }
}

/// The real migration, called from the Windows OS step of setup.
pub fn migrate_script_install() {
    Migration { procs: &SysProcesses, runner: &crate::bridge::SystemRunner, own_pid: std::process::id() }.run();
}

// ------------------------------------------------------------------ Uninstall

pub struct Uninstall<'a> {
    pub child: &'a dyn ChildSupervisor,
    pub runner: &'a dyn ProcessRunner,
    pub paths: &'a BridgePaths,
    pub remove_data: bool,
}

impl Uninstall<'_> {
    /// Stop the bridge first, then autostart, old task, firewall rule (one UAC), and data on request.
    /// Every step is tried; the first error is returned.
    pub fn run(&self) -> Result<(), String> {
        let mut first: Option<String> = None;
        let mut note = |r: Result<(), String>| {
            if let Err(e) = r {
                first.get_or_insert(e);
            }
        };
        note(self.child.stop());
        let reg = ["delete", RUN_KEY, "/v", RUN_VALUE, "/f"].map(String::from);
        let _ = self.runner.run(Path::new("reg"), &reg); // absent value is fine
        delete_task(self.runner);
        let fw = ["firewall".into(), "remove".into(), "--port".into(), self.paths.port().to_string()];
        note(match self.runner.run(&self.paths.exe, &fw) {
            Ok(o) if o.code == Some(0) => Ok(()),
            Ok(o) => Err(format!("firewall rule not removed: {}", o.stdout.trim())),
            Err(e) => Err(format!("could not start the bridge CLI: {e}")),
        });
        if self.remove_data {
            if let Some(base) = self.paths.config_dir.parent() {
                match std::fs::remove_dir_all(base) {
                    Err(e) if e.kind() != std::io::ErrorKind::NotFound => note(Err(e.to_string())),
                    _ => {}
                }
            }
        }
        first.map_or(Ok(()), Err)
    }
}

/// `--uninstall [--remove-data]` parsed from the command line.
pub fn uninstall_flags(args: &[String]) -> Option<bool> {
    args.iter().any(|a| a == "--uninstall").then(|| args.iter().any(|a| a == "--remove-data"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::CliOutput;
    use std::sync::{Arc, Mutex};

    type Log = Arc<Mutex<Vec<String>>>;

    #[test]
    fn old_script_processes_match_by_path_shaped_command_line_only() {
        assert!(runs_old_script(r"powershell.exe -File C:\x\bridge\windows\tray.ps1"));
        assert!(runs_old_script(r#"powershell -NoProfile -FILE "C:\Program Files\h\bridge\windows\run-bridge.ps1""#));
        assert!(!runs_old_script(r#"powershell -Command "... tray.ps1 ...""#));
        assert!(!runs_old_script(r"powershell install.ps1 -File x"));
        assert!(!runs_old_script("powershell -File tray.ps1"));

        let log: Log = Arc::default();
        struct Procs(Log);
        impl ProcessList for Procs {
            fn list(&self) -> Vec<ProcInfo> {
                let p = |pid, name: &str, cmd: &str| ProcInfo { pid, name: name.into(), cmd: cmd.into() };
                vec![
                    p(1, "powershell.exe", r"powershell -File C:\a\tray.ps1"),
                    p(2, "powershell.exe", r"powershell -File C:\a\tray.ps1"), // own pid
                    p(3, "notepad.exe", r"notepad -File C:\a\tray.ps1"),
                    p(4, "pwsh.exe", r"pwsh -File C:\a\run-bridge.ps1"),
                    p(5, "powershell.exe", "powershell -File tray.ps1"),
                ]
            }
            fn kill(&self, pid: u32) {
                self.0.lock().unwrap().push(format!("kill {pid}"));
            }
        }
        let runner = Runner(log.clone(), Some(1));
        Migration { procs: &Procs(log.clone()), runner: &runner, own_pid: 2 }.run();
        let l = log.lock().unwrap();
        assert_eq!(l[..2], ["kill 1", "kill 4"]);
    }

    struct Runner(Log, Option<i32>);
    impl ProcessRunner for Runner {
        fn run_with(
            &self,
            exe: &Path,
            args: &[String],
            _env: &[(String, String)],
            _cwd: Option<&Path>,
        ) -> std::io::Result<CliOutput> {
            self.0.lock().unwrap().push(format!("{} {}", exe.display(), args.join(" ")));
            Ok(CliOutput { code: self.1, stdout: String::new(), stderr: String::new() })
        }
    }

    struct NoProcs;
    impl ProcessList for NoProcs {
        fn list(&self) -> Vec<ProcInfo> {
            vec![]
        }
        fn kill(&self, _: u32) {}
    }

    #[test]
    fn task_deletion_argv_is_exact_and_an_absent_task_is_fine() {
        let log: Log = Arc::default();
        Migration { procs: &NoProcs, runner: &Runner(log.clone(), Some(0)), own_pid: 1 }.run();
        assert_eq!(
            *log.lock().unwrap(),
            ["schtasks /Query /TN Hermes Mobile Remote", "schtasks /Delete /TN Hermes Mobile Remote /F"]
        );
        let absent: Log = Arc::default();
        Migration { procs: &NoProcs, runner: &Runner(absent.clone(), Some(1)), own_pid: 1 }.run();
        assert_eq!(*absent.lock().unwrap(), ["schtasks /Query /TN Hermes Mobile Remote"]);
    }

    #[test]
    fn migration_leaves_the_data_dirs_alone() {
        let base = std::env::temp_dir().join(format!("hermes-mig-{}", std::process::id()));
        let cfg = base.join("config");
        std::fs::create_dir_all(&cfg).unwrap();
        std::fs::write(cfg.join("devices.json"), "x").unwrap();
        let log: Log = Arc::default();
        Migration { procs: &NoProcs, runner: &Runner(log, Some(0)), own_pid: 1 }.run();
        assert_eq!(std::fs::read_to_string(cfg.join("devices.json")).unwrap(), "x");
        std::fs::remove_dir_all(&base).unwrap();
    }

    struct Child(Log);
    impl ChildSupervisor for Child {
        fn spawn(&self) -> Result<(), String> {
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
    fn uninstall_stops_the_bridge_before_removing_autostart_and_the_firewall_rule() {
        let log: Log = Arc::default();
        let paths = BridgePaths {
            config_dir: "nowhere/hermes-remote/config".into(),
            venv: "v".into(),
            exe: "bridge.exe".into(),
        };
        let child = Child(log.clone());
        let runner = Runner(log.clone(), Some(0));
        Uninstall { child: &child, runner: &runner, paths: &paths, remove_data: false }.run().unwrap();
        let l = log.lock().unwrap();
        assert_eq!(l[0], "child.stop");
        assert!(l[1].starts_with("reg delete HKCU"), "{l:?}");
        assert_eq!(l.last().unwrap(), "bridge.exe firewall remove --port 8650");
        assert!(l.iter().any(|c| c == "schtasks /Delete /TN Hermes Mobile Remote /F"));
        assert_eq!(uninstall_flags(&["--uninstall".into(), "--remove-data".into()]), Some(true));
        assert_eq!(uninstall_flags(&["--uninstall".into()]), Some(false));
        assert_eq!(uninstall_flags(&[]), None);
    }
}
