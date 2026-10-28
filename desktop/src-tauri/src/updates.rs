//! App updates (D22, D30): NSIS/AppImage builds use the Tauri updater; `.deb` installs (and dev
//! builds) only check the newest `desktop-v*` GitHub release and link to it.

use semver::Version;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

pub const RELEASES_URL: &str = "https://api.github.com/repos/nideta231/hermes-mobile-remote/releases?per_page=20";
const TAG_PREFIX: &str = "desktop-v";
const MARKER: &str = "needs-resync";

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Bundle {
    Deb,
    AppImage,
    Nsis,
    Unknown,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Mode {
    Updater,
    ReleaseCheck,
}

impl Bundle {
    pub fn from_tauri(t: Option<tauri::utils::config::BundleType>) -> Bundle {
        use tauri::utils::config::BundleType as B;
        match t {
            Some(B::Deb) => Bundle::Deb,
            Some(B::AppImage) => Bundle::AppImage,
            Some(B::Nsis) => Bundle::Nsis,
            _ => Bundle::Unknown,
        }
    }

    pub fn mode(self) -> Mode {
        match self {
            Bundle::AppImage | Bundle::Nsis => Mode::Updater,
            Bundle::Deb | Bundle::Unknown => Mode::ReleaseCheck,
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
pub struct Release {
    pub tag_name: String,
    pub html_url: String,
}

pub trait ReleasesClient {
    fn list(&self) -> Result<Vec<Release>, String>;
}

pub trait Updater {
    /// The version of an available update, if any.
    fn check(&self) -> Result<Option<String>, String>;
    /// Download, install and restart the app.
    fn install_and_restart(&self) -> Result<(), String>;
}

/// `kind`: "link" (open `url`) or "restart" (call `update_install`).
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct UpdateInfo {
    pub version: String,
    pub kind: String,
    pub url: Option<String>,
}

pub struct UpdateCheck<'a> {
    pub bundle: Bundle,
    pub current: &'a str,
    pub releases: &'a dyn ReleasesClient,
    pub updater: &'a dyn Updater,
}

impl UpdateCheck<'_> {
    /// A newer version, or None (also when the check fails: the UI stays calm).
    pub fn check(&self) -> Option<UpdateInfo> {
        let current = Version::parse(self.current).ok()?;
        let found = match self.bundle.mode() {
            Mode::Updater => self.updater.check().map(|v| {
                v.and_then(|v| Version::parse(&v).ok().filter(|n| *n > current)).map(|n| UpdateInfo {
                    version: n.to_string(),
                    kind: "restart".into(),
                    url: None,
                })
            }),
            Mode::ReleaseCheck => self.releases.list().map(|rs| {
                rs.into_iter()
                    .filter_map(|r| {
                        let v = Version::parse(r.tag_name.strip_prefix(TAG_PREFIX)?).ok()?;
                        (v > current).then_some((v, r.html_url))
                    })
                    .max_by(|a, b| a.0.cmp(&b.0))
                    .map(|(v, url)| UpdateInfo { version: v.to_string(), kind: "link".into(), url: Some(url) })
            }),
        };
        found.unwrap_or_else(|e| {
            eprintln!("update check failed: {e}");
            None
        })
    }

    /// Leave the re-sync marker, then download, install and restart.
    pub fn install(&self, config_dir: &Path) -> Result<(), String> {
        std::fs::create_dir_all(config_dir).map_err(|e| e.to_string())?;
        std::fs::write(config_dir.join(MARKER), b"").map_err(|e| e.to_string())?;
        self.updater.install_and_restart()
    }
}

pub fn needs_resync(config_dir: &Path) -> bool {
    config_dir.join(MARKER).exists()
}

pub fn clear_resync(config_dir: &Path) {
    let _ = std::fs::remove_file(config_dir.join(MARKER));
}

// ------------------------------------------------------------------ real deps

pub struct GithubReleases;

impl ReleasesClient for GithubReleases {
    fn list(&self) -> Result<Vec<Release>, String> {
        let mut resp = ureq::get(RELEASES_URL)
            .header("User-Agent", "hermes-remote-desktop")
            .header("Accept", "application/vnd.github+json")
            .call()
            .map_err(|e| e.to_string())?;
        resp.body_mut().read_json::<Vec<Release>>().map_err(|e| e.to_string())
    }
}

pub struct TauriUpdater {
    pub app: tauri::AppHandle,
}

impl Updater for TauriUpdater {
    fn check(&self) -> Result<Option<String>, String> {
        use tauri_plugin_updater::UpdaterExt;
        tauri::async_runtime::block_on(async {
            let u = self.app.updater().map_err(|e| e.to_string())?;
            Ok(u.check().await.map_err(|e| e.to_string())?.map(|u| u.version))
        })
    }

    fn install_and_restart(&self) -> Result<(), String> {
        use tauri_plugin_updater::UpdaterExt;
        tauri::async_runtime::block_on(async {
            let u = self.app.updater().map_err(|e| e.to_string())?;
            let update = u.check().await.map_err(|e| e.to_string())?.ok_or("no update available")?;
            update.download_and_install(|_, _| {}, || {}).await.map_err(|e| e.to_string())
        })?;
        self.app.restart()
    }
}

fn config_dir() -> PathBuf {
    crate::bridge::BridgePaths::from_env().config_dir
}

/// A newer version or null; "link" on `.deb` (open `url`), "restart" on updater builds.
#[tauri::command]
pub async fn update_check(app: tauri::AppHandle) -> Result<Option<UpdateInfo>, String> {
    let bundle = Bundle::from_tauri(tauri::utils::platform::bundle_type());
    tauri::async_runtime::spawn_blocking(move || {
        UpdateCheck {
            bundle,
            current: crate::version::app_version(),
            releases: &GithubReleases,
            updater: &TauriUpdater { app },
        }
        .check()
    })
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn update_install(app: tauri::AppHandle) -> Result<(), String> {
    let bundle = Bundle::from_tauri(tauri::utils::platform::bundle_type());
    tauri::async_runtime::spawn_blocking(move || {
        UpdateCheck {
            bundle,
            current: crate::version::app_version(),
            releases: &GithubReleases,
            updater: &TauriUpdater { app },
        }
        .install(&config_dir())
    })
    .await
    .map_err(|e| e.to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    struct FakeReleases(Result<Vec<Release>, String>, RefCell<u32>);
    impl ReleasesClient for FakeReleases {
        fn list(&self) -> Result<Vec<Release>, String> {
            *self.1.borrow_mut() += 1;
            self.0.clone()
        }
    }

    struct FakeUpdater {
        version: Option<String>,
        log: RefCell<Vec<String>>,
    }
    impl Updater for FakeUpdater {
        fn check(&self) -> Result<Option<String>, String> {
            self.log.borrow_mut().push("check".into());
            Ok(self.version.clone())
        }
        fn install_and_restart(&self) -> Result<(), String> {
            self.log.borrow_mut().push("install".into());
            Ok(())
        }
    }

    fn rel(tag: &str) -> Release {
        Release { tag_name: tag.into(), html_url: format!("https://example.test/{tag}") }
    }
    fn releases(tags: &[&str]) -> FakeReleases {
        FakeReleases(Ok(tags.iter().map(|t| rel(t)).collect()), RefCell::new(0))
    }
    fn updater(v: Option<&str>) -> FakeUpdater {
        FakeUpdater { version: v.map(String::from), log: RefCell::new(vec![]) }
    }

    #[test]
    fn bundle_type_picks_updater_or_release_check() {
        assert_eq!(Bundle::Nsis.mode(), Mode::Updater);
        assert_eq!(Bundle::AppImage.mode(), Mode::Updater);
        assert_eq!(Bundle::Deb.mode(), Mode::ReleaseCheck);
        assert_eq!(Bundle::Unknown.mode(), Mode::ReleaseCheck);

        let (r, u) = (releases(&["desktop-v0.2.0"]), updater(Some("9.9.9")));
        let deb = UpdateCheck { bundle: Bundle::Deb, current: "0.1.0", releases: &r, updater: &u };
        let info = deb.check().unwrap();
        assert_eq!(info.kind, "link");
        assert!(u.log.borrow().is_empty());

        let (r, u) = (releases(&["desktop-v0.2.0"]), updater(Some("0.3.0")));
        let app = UpdateCheck { bundle: Bundle::AppImage, current: "0.1.0", releases: &r, updater: &u };
        let info = app.check().unwrap();
        assert_eq!((info.kind.as_str(), info.version.as_str(), info.url), ("restart", "0.3.0", None));
        assert_eq!(*r.1.borrow(), 0);
    }

    #[test]
    fn banner_only_for_a_newer_desktop_release_by_semver() {
        let tags = ["v1.25.0", "desktop-v0.2.0", "desktop-v0.10.0", "desktop-v0.0.9", "desktop-vbad"];
        let (r, u) = (releases(&tags), updater(None));
        let c = |cur| UpdateCheck { bundle: Bundle::Deb, current: cur, releases: &r, updater: &u }.check();
        let info = c("0.1.0").unwrap();
        assert_eq!(info.version, "0.10.0");
        assert_eq!(info.url.as_deref(), Some("https://example.test/desktop-v0.10.0"));
        assert_eq!(c("0.10.0"), None);
        assert_eq!(c("1.0.0"), None);

        let none = releases(&["v1.25.0"]);
        assert_eq!(UpdateCheck { bundle: Bundle::Deb, current: "0.1.0", releases: &none, updater: &u }.check(), None);
        let err = FakeReleases(Err("offline".into()), RefCell::new(0));
        assert_eq!(UpdateCheck { bundle: Bundle::Deb, current: "0.1.0", releases: &err, updater: &u }.check(), None);

        let same = updater(Some("0.1.0"));
        let app = UpdateCheck { bundle: Bundle::Nsis, current: "0.1.0", releases: &r, updater: &same };
        assert_eq!(app.check(), None);
    }

    #[test]
    fn install_leaves_the_resync_marker_then_installs() {
        let dir = tempfile::tempdir().unwrap();
        let (r, u) = (releases(&[]), updater(Some("0.2.0")));
        let app = UpdateCheck { bundle: Bundle::Nsis, current: "0.1.0", releases: &r, updater: &u };
        assert!(!needs_resync(dir.path()));
        app.install(dir.path()).unwrap();
        assert!(needs_resync(dir.path()));
        assert_eq!(*u.log.borrow(), vec!["install"]);
        clear_resync(dir.path());
        assert!(!needs_resync(dir.path()));
    }
}
