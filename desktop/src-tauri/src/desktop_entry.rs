//! Launcher entry for the AppImage. An AppImage is one loose file, so nothing puts it in the app
//! menu (the .deb ships its own entry). On every start from an AppImage the app writes
//! `$XDG_DATA_HOME/applications/hermes-remote.desktop` pointing at `$APPIMAGE`, plus its icon, so
//! the entry follows the file if the user moves it.

use std::path::{Path, PathBuf};

pub const FILE_NAME: &str = "hermes-remote.desktop";
const ICON_NAME: &str = "hermes-remote-desktop";
const ICON_PNG: &[u8] = include_bytes!("../icons/128x128.png");

/// The `.desktop` text for an AppImage at `appimage`.
pub fn render(appimage: &Path) -> String {
    // Exec quoting rules (Desktop Entry spec): wrap in double quotes, escape `"`, `` ` ``, `$`, `\`.
    let mut exec = String::from("\"");
    for c in appimage.to_string_lossy().chars() {
        if matches!(c, '"' | '`' | '$' | '\\') {
            exec.push('\\');
        }
        exec.push(c);
    }
    exec.push('"');
    format!(
        "[Desktop Entry]\nType=Application\nName=Hermes Remote\nComment=Control the Hermes Mobile Remote bridge\n\
         Exec={exec}\nIcon={ICON_NAME}\nCategories=Utility;\nTerminal=false\nStartupWMClass=hermes-remote-desktop\n"
    )
}

/// `$XDG_DATA_HOME` or `~/.local/share`.
pub fn data_home(xdg_data_home: Option<String>, home: &str) -> PathBuf {
    match xdg_data_home.filter(|v| !v.is_empty()) {
        Some(d) => PathBuf::from(d),
        None => Path::new(home).join(".local/share"),
    }
}

/// Write the entry and the icon under `data_home`. Returns the entry's path.
pub fn install(data_home: &Path, appimage: &Path) -> std::io::Result<PathBuf> {
    let apps = data_home.join("applications");
    let icons = data_home.join("icons/hicolor/128x128/apps");
    std::fs::create_dir_all(&apps)?;
    std::fs::create_dir_all(&icons)?;
    let icon = icons.join(format!("{ICON_NAME}.png"));
    if std::fs::read(&icon).ok().as_deref() != Some(ICON_PNG) {
        std::fs::write(&icon, ICON_PNG)?;
    }
    let entry = apps.join(FILE_NAME);
    let text = render(appimage);
    if std::fs::read_to_string(&entry).ok().as_deref() != Some(text.as_str()) {
        std::fs::write(&entry, text)?;
    }
    Ok(entry)
}

/// Set on the detached copy so it does not detach again.
pub const DETACHED_ENV: &str = "HERMES_REMOTE_DETACHED";

/// Started from a terminal as an AppImage: run a detached copy and give the terminal back.
/// `--foreground` keeps it attached (for logs).
pub fn should_detach(appimage: bool, stdin_tty: bool, already: bool, args: &[String]) -> bool {
    appimage && stdin_tty && !already && !args.iter().any(|a| a == "--foreground")
}

/// Re-launch `$APPIMAGE` in its own session with no terminal, then exit this process.
pub fn detach_if_in_terminal(args: &[String]) {
    use std::os::unix::process::CommandExt;
    use std::process::{Command, Stdio};
    let Some(appimage) = std::env::var_os("APPIMAGE") else { return };
    // SAFETY: isatty only reads the descriptor's state.
    let tty = unsafe { libc::isatty(0) } == 1;
    if !should_detach(true, tty, std::env::var_os(DETACHED_ENV).is_some(), args) {
        return;
    }
    let mut cmd = Command::new(&appimage);
    crate::bridge::scrub_appimage_env(&mut cmd, true);
    for k in ["APPDIR", "APPIMAGE", "ARGV0", "OWD"] {
        cmd.env_remove(k);
    }
    cmd.args(&args[1..]).env(DETACHED_ENV, "1").stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
    // SAFETY: setsid is async-signal-safe; it only leaves the terminal's session.
    unsafe {
        cmd.pre_exec(|| {
            libc::setsid();
            Ok(())
        });
    }
    if cmd.spawn().is_ok() {
        println!("Hermes Remote is running; find it in the app menu or the tray.");
        std::process::exit(0);
    }
}

/// Called at app start: only does something when running from an AppImage.
pub fn on_app_start() {
    let Some(appimage) = std::env::var_os("APPIMAGE") else { return };
    let home = std::env::var("HOME").unwrap_or_default();
    let dh = data_home(std::env::var("XDG_DATA_HOME").ok(), &home);
    if let Err(e) = install(&dh, Path::new(&appimage)) {
        eprintln!("launcher entry: {e}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exec_quotes_a_path_with_spaces_and_specials() {
        let t = render(Path::new("/home/u/Apps/Hermes Remote_0.1.0_amd64.AppImage"));
        assert!(t.contains("Exec=\"/home/u/Apps/Hermes Remote_0.1.0_amd64.AppImage\"\n"));
        let t = render(Path::new("/x/a$b\"c.AppImage"));
        assert!(t.contains(r#"Exec="/x/a\$b\"c.AppImage""#));
        assert!(t.contains("Icon=hermes-remote-desktop\n"));
    }

    #[test]
    fn data_home_follows_xdg_or_falls_back() {
        assert_eq!(data_home(Some("/d".into()), "/home/u"), PathBuf::from("/d"));
        assert_eq!(data_home(Some(String::new()), "/home/u"), PathBuf::from("/home/u/.local/share"));
        assert_eq!(data_home(None, "/home/u"), PathBuf::from("/home/u/.local/share"));
    }

    #[test]
    fn install_writes_entry_and_icon_and_follows_a_moved_appimage() {
        let d = std::env::temp_dir().join(format!("hr-entry-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        let e = install(&d, Path::new("/a/one.AppImage")).unwrap();
        assert!(std::fs::read_to_string(&e).unwrap().contains("/a/one.AppImage"));
        assert_eq!(std::fs::read(d.join("icons/hicolor/128x128/apps/hermes-remote-desktop.png")).unwrap(), ICON_PNG);
        install(&d, Path::new("/b/two.AppImage")).unwrap();
        let t = std::fs::read_to_string(&e).unwrap();
        assert!(t.contains("/b/two.AppImage") && !t.contains("/a/one"));
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn detaches_only_an_appimage_started_from_a_terminal() {
        let a = |x: &[&str]| x.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        assert!(should_detach(true, true, false, &a(&["app"])));
        assert!(!should_detach(false, true, false, &a(&["app"])), "deb/dev build");
        assert!(!should_detach(true, false, false, &a(&["app"])), "launched from the menu");
        assert!(!should_detach(true, true, true, &a(&["app"])), "the detached copy");
        assert!(!should_detach(true, true, false, &a(&["app", "--foreground"])));
    }
}
