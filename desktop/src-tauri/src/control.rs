//! Controls whose OS backend arrives in later tickets (bridge start/stop, start at logon, firewall fix,
//! open log folder). Commands call a `Control`; later tickets replace the implementation, not the commands.

use crate::bridge::{config_value, BridgePaths};
use serde_json::{json, Value};
use std::path::Path;

pub const UNSUPPORTED: &str = "not available yet on this platform";

pub trait Control {
    fn start(&self) -> Result<(), String>;
    fn stop(&self) -> Result<(), String>;
    fn start_at_logon(&self) -> bool;
    fn set_start_at_logon(&self, on: bool) -> Result<(), String>;
    fn firewall_fix(&self) -> Result<(), String>;
    fn open_logs(&self) -> Result<(), String>;
}

pub struct Unsupported;

impl Control for Unsupported {
    fn start(&self) -> Result<(), String> {
        Err(UNSUPPORTED.into())
    }
    fn stop(&self) -> Result<(), String> {
        Err(UNSUPPORTED.into())
    }
    fn start_at_logon(&self) -> bool {
        false
    }
    fn set_start_at_logon(&self, _on: bool) -> Result<(), String> {
        Err(UNSUPPORTED.into())
    }
    fn firewall_fix(&self) -> Result<(), String> {
        Err(UNSUPPORTED.into())
    }
    fn open_logs(&self) -> Result<(), String> {
        Err(UNSUPPORTED.into())
    }
}

/// The text of `config_text` with the `terminal_shell` line set to `value` (added if missing).
pub fn with_terminal_shell(config_text: &str, value: &str) -> String {
    let line = format!("terminal_shell = {}", json!(value));
    let mut found = false;
    let mut lines: Vec<String> = config_text
        .lines()
        .map(|l| {
            if l.split_once('=').map(|(k, _)| k.trim() == "terminal_shell").unwrap_or(false) {
                found = true;
                line.clone()
            } else {
                l.to_string()
            }
        })
        .collect();
    if !found {
        lines.push(line);
    }
    lines.join("\n") + "\n"
}

pub fn read_terminal_shell(config_text: &str) -> Option<String> {
    config_value(config_text, "terminal_shell")
}

/// Write the `terminal_shell` line through `writer(path, text)`.
pub fn set_terminal_shell_in(
    config_dir: &Path,
    value: &str,
    writer: &dyn Fn(&Path, &str) -> std::io::Result<()>,
) -> Result<(), String> {
    let path = config_dir.join("config.toml");
    let old = std::fs::read_to_string(&path).unwrap_or_default();
    writer(&path, &with_terminal_shell(&old, value)).map_err(|e| e.to_string())
}

pub fn settings_in(config_dir: &Path, control: &dyn Control) -> Value {
    let text = std::fs::read_to_string(config_dir.join("config.toml")).unwrap_or_default();
    json!({"start_at_logon": control.start_at_logon(),"terminal_shell": read_terminal_shell(&text)})
}

/// `<config_dir>/INSTALLED.txt`, or None when setup has not run.
pub fn installed_list_in(config_dir: &Path) -> Option<String> {
    std::fs::read_to_string(config_dir.join("INSTALLED.txt")).ok()
}

fn backend() -> impl Control {
    Unsupported
}

#[tauri::command]
pub async fn bridge_start() -> Result<(), String> {
    backend().start()
}
#[tauri::command]
pub async fn bridge_stop() -> Result<(), String> {
    backend().stop()
}
#[tauri::command]
pub async fn get_settings() -> Result<Value, String> {
    Ok(settings_in(&BridgePaths::from_env().config_dir, &backend()))
}
#[tauri::command]
pub async fn set_start_at_logon(on: bool) -> Result<(), String> {
    backend().set_start_at_logon(on)
}
#[tauri::command]
pub async fn firewall_fix() -> Result<(), String> {
    backend().firewall_fix()
}
#[tauri::command]
pub async fn open_logs() -> Result<(), String> {
    backend().open_logs()
}
#[tauri::command]
pub async fn set_terminal_shell(value: String) -> Result<(), String> {
    set_terminal_shell_in(&BridgePaths::from_env().config_dir, &value, &|p, t| {
        std::fs::create_dir_all(p.parent().unwrap_or(Path::new(".")))?;
        std::fs::write(p, t)
    })
}
#[tauri::command]
pub async fn get_installed_list() -> Result<Option<String>, String> {
    Ok(installed_list_in(&BridgePaths::from_env().config_dir))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    #[test]
    fn unsupported_says_not_available_yet() {
        let c = Unsupported;
        assert_eq!(c.start().unwrap_err(), "not available yet on this platform");
        assert_eq!(c.stop().unwrap_err(), UNSUPPORTED);
        assert_eq!(c.set_start_at_logon(true).unwrap_err(), UNSUPPORTED);
        assert_eq!(c.firewall_fix().unwrap_err(), UNSUPPORTED);
        assert_eq!(c.open_logs().unwrap_err(), UNSUPPORTED);
    }

    #[test]
    fn terminal_shell_line_is_replaced_or_added_through_the_writer() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("config.toml"), "port = 9000\nterminal_shell = \"bash\"\n").unwrap();
        let wrote = Mutex::new(Vec::new());
        set_terminal_shell_in(dir.path(), "zsh", &|p, t| {
            wrote.lock().unwrap().push((p.to_path_buf(), t.to_string()));
            Ok(())
        })
        .unwrap();
        let w = wrote.lock().unwrap();
        assert_eq!(w[0].0, dir.path().join("config.toml"));
        assert_eq!(w[0].1, "port = 9000\nterminal_shell = \"zsh\"\n");
        assert_eq!(with_terminal_shell("port = 1\n", "fish"), "port = 1\nterminal_shell = \"fish\"\n");
        assert_eq!(read_terminal_shell("terminal_shell = \"zsh\""), Some("zsh".into()));
    }

    #[test]
    fn installed_list_is_none_until_setup_has_run() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(installed_list_in(dir.path()), None);
        std::fs::write(dir.path().join("INSTALLED.txt"), "a venv\n").unwrap();
        assert_eq!(installed_list_in(dir.path()), Some("a venv\n".into()));
    }
}
