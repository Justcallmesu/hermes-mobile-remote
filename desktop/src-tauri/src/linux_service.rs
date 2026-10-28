//! Always-on mode on Linux: the bridge runs as a systemd user service (`hermes-remote-bridge.service`),
//! so closing or crashing the app never stops it. "Keep running after logout" is user linger.
//! Everything goes through an injected `ProcessRunner`; tests never run the real systemctl/loginctl.

use crate::bridge::{BridgePaths, CliOutput, ProcessRunner, SystemRunner};
use crate::control::{Control, UNSUPPORTED};
use crate::setup::OsStep;
use std::path::{Path, PathBuf};

pub const UNIT_NAME: &str = "hermes-remote-bridge.service";
/// The unit template shared with `install.sh`; `@BIN@` is the bridge entry point.
pub const UNIT_TEMPLATE: &str = include_str!("../../../bridge/systemd/hermes-remote-bridge.service");

pub trait ServiceControl {
    /// `systemctl --user is-active`: true when active.
    fn status(&self) -> Result<bool, String>;
    fn start(&self) -> Result<(), String>;
    fn stop(&self) -> Result<(), String>;
    /// Start at login.
    fn enable(&self) -> Result<(), String>;
    fn disable(&self) -> Result<(), String>;
    /// `enable` then `restart`: used by setup. Restart (not `--now`) so a bridge an old
    /// `install.sh` already started is replaced by the new unit's binary.
    fn enable_now(&self) -> Result<(), String>;
    fn is_enabled(&self) -> bool;
    /// Keep running after logout.
    fn linger_on(&self) -> Result<(), String>;
    fn linger_off(&self) -> Result<(), String>;
    fn linger(&self) -> bool;
    /// Write the unit for `bin`, then `daemon-reload`.
    fn write_unit(&self, bin: &Path) -> Result<(), String>;
}

/// The unit text: the template with `@BIN@` replaced.
pub fn render_unit(template: &str, bin: &Path) -> String {
    template.replace("@BIN@", &bin.to_string_lossy())
}

/// The unit's `ReadWritePaths=` directories with `%h` replaced by `home`.
pub fn read_write_paths(unit_text: &str, home: &Path) -> Vec<PathBuf> {
    unit_text
        .lines()
        .filter_map(|l| l.trim().strip_prefix("ReadWritePaths="))
        .flat_map(|v| v.split_whitespace())
        .map(|p| PathBuf::from(p.replace("%h", &home.to_string_lossy())))
        .collect()
}

/// `$XDG_CONFIG_HOME/systemd/user`, default `~/.config/systemd/user`.
pub fn unit_dir(env: &dyn Fn(&str) -> Option<String>) -> PathBuf {
    let get = |k: &str| env(k).filter(|v| !v.is_empty());
    let base = match get("XDG_CONFIG_HOME") {
        Some(x) => PathBuf::from(x),
        None => PathBuf::from(get("HOME").unwrap_or_default()).join(".config"),
    };
    base.join("systemd/user")
}

pub struct Systemd<'a> {
    pub runner: &'a dyn ProcessRunner,
    pub unit_dir: PathBuf,
    pub user: String,
}

impl Systemd<'static> {
    pub fn from_env() -> Systemd<'static> {
        let env = |k: &str| std::env::var(k).ok();
        Systemd { runner: &SystemRunner, unit_dir: unit_dir(&env), user: env("USER").unwrap_or_default() }
    }
}

impl Systemd<'_> {
    fn run(&self, exe: &str, args: &[&str]) -> Result<CliOutput, String> {
        let args: Vec<String> = args.iter().map(|s| s.to_string()).collect();
        self.runner.run(Path::new(exe), &args).map_err(|e| format!("{exe} could not start: {e}"))
    }

    fn systemctl(&self, args: &[&str]) -> Result<CliOutput, String> {
        let mut a = vec!["--user"];
        a.extend_from_slice(args);
        self.run("systemctl", &a)
    }

    fn systemctl_ok(&self, args: &[&str]) -> Result<(), String> {
        let out = self.systemctl(args)?;
        if out.code == Some(0) {
            Ok(())
        } else {
            Err(format!("systemctl --user {} failed: {}", args.join(" "), out.stderr.trim()))
        }
    }

    fn loginctl(&self, verb: &str) -> Result<(), String> {
        let out = self.run("loginctl", &[verb, &self.user])?;
        if out.code == Some(0) {
            Ok(())
        } else {
            Err(format!("needs your password: run `loginctl {verb}` in a terminal"))
        }
    }
}

impl ServiceControl for Systemd<'_> {
    fn status(&self) -> Result<bool, String> {
        Ok(self.systemctl(&["is-active", UNIT_NAME])?.stdout.trim() == "active")
    }
    fn start(&self) -> Result<(), String> {
        self.systemctl_ok(&["start", UNIT_NAME])
    }
    fn stop(&self) -> Result<(), String> {
        self.systemctl_ok(&["stop", UNIT_NAME])
    }
    fn enable(&self) -> Result<(), String> {
        self.systemctl_ok(&["enable", UNIT_NAME])
    }
    fn disable(&self) -> Result<(), String> {
        self.systemctl_ok(&["disable", UNIT_NAME])
    }
    fn enable_now(&self) -> Result<(), String> {
        self.systemctl_ok(&["enable", UNIT_NAME])?;
        self.systemctl_ok(&["restart", UNIT_NAME])
    }
    fn is_enabled(&self) -> bool {
        self.systemctl(&["is-enabled", UNIT_NAME]).map(|o| o.stdout.trim() == "enabled").unwrap_or(false)
    }
    fn linger_on(&self) -> Result<(), String> {
        self.loginctl("enable-linger")
    }
    fn linger_off(&self) -> Result<(), String> {
        self.loginctl("disable-linger")
    }
    fn linger(&self) -> bool {
        self.run("loginctl", &["show-user", &self.user, "-p", "Linger"])
            .map(|o| o.stdout.trim() == "Linger=yes")
            .unwrap_or(false)
    }
    fn write_unit(&self, bin: &Path) -> Result<(), String> {
        std::fs::create_dir_all(&self.unit_dir).map_err(|e| format!("{}: {e}", self.unit_dir.display()))?;
        let text = render_unit(UNIT_TEMPLATE, bin);
        // systemd refuses to start the sandboxed unit (226/NAMESPACE) when a ReadWritePaths dir is missing.
        let home = std::env::var("HOME").unwrap_or_default();
        for dir in read_write_paths(&text, Path::new(&home)) {
            std::fs::create_dir_all(&dir).map_err(|e| format!("{}: {e}", dir.display()))?;
        }
        let path = self.unit_dir.join(UNIT_NAME);
        std::fs::write(&path, text).map_err(|e| format!("{}: {e}", path.display()))?;
        self.systemctl_ok(&["daemon-reload"])
    }
}

/// The app's controls on Linux: the switch and "start at login" drive the user service.
pub struct LinuxControl<S: ServiceControl>(pub S);

impl<S: ServiceControl> Control for LinuxControl<S> {
    fn start(&self) -> Result<(), String> {
        self.0.start()
    }
    fn stop(&self) -> Result<(), String> {
        self.0.stop()
    }
    fn start_at_logon(&self) -> bool {
        self.0.is_enabled()
    }
    fn set_start_at_logon(&self, on: bool) -> Result<(), String> {
        if on {
            self.0.enable()
        } else {
            self.0.disable()
        }
    }
    fn firewall_fix(&self) -> Result<(), String> {
        Err(UNSUPPORTED.into())
    }
    fn open_logs(&self) -> Result<(), String> {
        Err(UNSUPPORTED.into())
    }
}

/// Setup's OS step: write the unit, daemon-reload, enable, restart. An existing unit of the same name
/// (from `install.sh`) is taken over, and a bridge it already started is restarted on the new venv.
pub struct LinuxOsStep<S: ServiceControl> {
    pub service: S,
    pub bin: PathBuf,
}

impl<S: ServiceControl> OsStep for LinuxOsStep<S> {
    fn describe(&self) -> Vec<String> {
        vec![format!("systemd user service {UNIT_NAME}, enabled at login")]
    }
    fn apply(&self) -> Result<(), String> {
        self.service.write_unit(&self.bin)?;
        self.service.enable_now()
    }
    fn stop_bridge(&self) -> Result<(), String> {
        self.service.stop()
    }
    fn start_bridge(&self) -> Result<(), String> {
        self.service.start()
    }
}

pub fn real_os_step() -> LinuxOsStep<Systemd<'static>> {
    LinuxOsStep { service: Systemd::from_env(), bin: BridgePaths::from_env().exe }
}

#[tauri::command]
pub async fn get_linger() -> Result<bool, String> {
    Ok(Systemd::from_env().linger())
}

#[tauri::command]
pub async fn set_linger(on: bool) -> Result<(), String> {
    let s = Systemd::from_env();
    if on {
        s.linger_on()
    } else {
        s.linger_off()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    struct Fake {
        calls: Mutex<Vec<String>>,
        /// (argv prefix, code, stdout)
        replies: Vec<(&'static str, i32, &'static str)>,
    }
    impl Fake {
        fn new(replies: Vec<(&'static str, i32, &'static str)>) -> Fake {
            Fake { calls: Mutex::new(vec![]), replies }
        }
        fn calls(&self) -> Vec<String> {
            self.calls.lock().unwrap().clone()
        }
    }
    impl ProcessRunner for Fake {
        fn run_with(&self, exe: &Path, args: &[String], _: &[(String, String)], _: Option<&Path>) -> std::io::Result<CliOutput> {
            let line = format!("{} {}", exe.display(), args.join(" "));
            self.calls.lock().unwrap().push(line.clone());
            let (code, out) = self.replies.iter().find(|r| line.starts_with(r.0)).map(|r| (r.1, r.2)).unwrap_or((0, ""));
            Ok(CliOutput { code: Some(code), stdout: out.into(), stderr: String::new() })
        }
    }

    fn sd<'a>(f: &'a Fake, dir: &Path) -> Systemd<'a> {
        Systemd { runner: f, unit_dir: dir.to_path_buf(), user: "u".into() }
    }

    #[test]
    fn unit_is_the_template_with_bin_replaced_then_reloaded_and_enabled_now() {
        let dir = tempfile::tempdir().unwrap();
        let f = Fake::new(vec![]);
        let step = LinuxOsStep { service: sd(&f, dir.path()), bin: PathBuf::from("/v/bin/hermes-remote-bridge") };
        step.apply().unwrap();
        let text = std::fs::read_to_string(dir.path().join("hermes-remote-bridge.service")).unwrap();
        let template = std::fs::read_to_string(
            Path::new(env!("CARGO_MANIFEST_DIR")).join("../../bridge/systemd/hermes-remote-bridge.service"),
        )
        .unwrap();
        assert_eq!(text, template.replace("@BIN@", "/v/bin/hermes-remote-bridge"));
        assert!(text.contains("ExecStart=/v/bin/hermes-remote-bridge serve"));
        assert_eq!(
            f.calls(),
            [
                "systemctl --user daemon-reload",
                "systemctl --user enable hermes-remote-bridge.service",
                "systemctl --user restart hermes-remote-bridge.service"
            ]
        );
        assert_eq!(step.describe(), ["systemd user service hermes-remote-bridge.service, enabled at login"]);
        assert_eq!(
            unit_dir(&|k| (k == "HOME").then(|| "/home/u".to_string())),
            PathBuf::from("/home/u/.config/systemd/user")
        );
        assert_eq!(unit_dir(&|k| (k == "XDG_CONFIG_HOME").then(|| "/c".to_string())), PathBuf::from("/c/systemd/user"));
    }

    #[test]
    fn switch_maps_to_start_stop_and_status_is_parsed_from_is_active() {
        let dir = tempfile::tempdir().unwrap();
        let f = Fake::new(vec![("systemctl --user is-active", 3, "inactive\n")]);
        let c = LinuxControl(sd(&f, dir.path()));
        c.start().unwrap();
        c.stop().unwrap();
        assert_eq!(c.0.status(), Ok(false));
        let g = Fake::new(vec![("systemctl --user is-active", 0, "active\n")]);
        assert_eq!(sd(&g, dir.path()).status(), Ok(true));
        assert_eq!(
            f.calls(),
            [
                "systemctl --user start hermes-remote-bridge.service",
                "systemctl --user stop hermes-remote-bridge.service",
                "systemctl --user is-active hermes-remote-bridge.service"
            ]
        );
    }

    #[test]
    fn start_at_login_and_linger_argv_are_exact_and_polkit_refusal_is_explained() {
        let dir = tempfile::tempdir().unwrap();
        let f = Fake::new(vec![
            ("systemctl --user is-enabled", 0, "enabled\n"),
            ("loginctl show-user", 0, "Linger=yes\n"),
        ]);
        let c = LinuxControl(sd(&f, dir.path()));
        c.set_start_at_logon(true).unwrap();
        c.set_start_at_logon(false).unwrap();
        assert!(c.start_at_logon());
        c.0.linger_on().unwrap();
        c.0.linger_off().unwrap();
        assert!(c.0.linger());
        assert_eq!(
            f.calls(),
            [
                "systemctl --user enable hermes-remote-bridge.service",
                "systemctl --user disable hermes-remote-bridge.service",
                "systemctl --user is-enabled hermes-remote-bridge.service",
                "loginctl enable-linger u",
                "loginctl disable-linger u",
                "loginctl show-user u -p Linger"
            ]
        );
        let denied = Fake::new(vec![("loginctl enable-linger", 1, "")]);
        assert_eq!(
            sd(&denied, dir.path()).linger_on().unwrap_err(),
            "needs your password: run `loginctl enable-linger` in a terminal"
        );
    }

    #[test]
    fn the_unit_sandbox_dirs_are_read_from_the_template() {
        let dirs = read_write_paths(&render_unit(UNIT_TEMPLATE, Path::new("/v/bin/b")), Path::new("/home/u"));
        assert_eq!(
            dirs,
            [PathBuf::from("/home/u/.config/hermes-remote"), PathBuf::from("/home/u/.local/state/hermes-remote")]
        );
    }
}
