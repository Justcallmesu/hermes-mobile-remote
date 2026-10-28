//! First-run setup: change list, uv (found or downloaded and hash-checked), bridge source copy,
//! `uv sync --frozen`, the OS step, then waiting for the bridge to answer.
//! Everything outside the logic is injected (`Downloader`, `ProcessRunner`, `Fs`, `OsStep`, `BridgeApi`).

use crate::bridge::{BridgeApi, BridgePaths, LocalHttp, Os, ProcessRunner, SystemRunner};
use serde::Serialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

pub const UV_VERSION: &str = "0.13.6";
/// Directory names left out when the bundled bridge source is copied.
pub const COPY_SKIP: [&str; 4] = [".venv", "__pycache__", ".pytest_cache", "tests"];
pub const BRIDGE_WAIT: Duration = Duration::from_secs(20);

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Progress {
    pub step: String,
    /// "running" | "done" | "error"
    pub state: String,
    pub message: String,
}

pub trait Downloader {
    fn get(&self, url: &str) -> Result<Vec<u8>, String>;
}

pub trait Fs {
    fn create_dir_all(&self, p: &Path) -> Result<(), String>;
    fn write(&self, p: &Path, bytes: &[u8]) -> Result<(), String>;
    fn exists(&self, p: &Path) -> bool;
    fn copy_dir(&self, from: &Path, to: &Path, skip: &[&str]) -> Result<(), String>;
    fn set_executable(&self, p: &Path) -> Result<(), String>;
    /// A program on PATH.
    fn which(&self, name: &str) -> Option<PathBuf>;
}

/// The OS-specific last step (autostart / systemd unit, Windows task). Tickets 20 and 32 implement it.
pub trait OsStep {
    /// Lines for the change list.
    fn describe(&self) -> Vec<String>;
    fn apply(&self) -> Result<(), String>;
    fn stop_bridge(&self) -> Result<(), String>;
    fn start_bridge(&self) -> Result<(), String>;
}

pub struct NoOsStep;

impl OsStep for NoOsStep {
    fn describe(&self) -> Vec<String> {
        vec!["Autostart and firewall rule: not available yet on this platform".into()]
    }
    fn apply(&self) -> Result<(), String> {
        Ok(())
    }
    fn stop_bridge(&self) -> Result<(), String> {
        Ok(())
    }
    fn start_bridge(&self) -> Result<(), String> {
        Ok(())
    }
}

// ------------------------------------------------------------------ uv asset

#[derive(Debug, Clone, PartialEq)]
pub struct UvAsset {
    pub url: String,
    pub ext: &'static str,
    /// Path of the uv binary inside the archive.
    pub inner: String,
    pub exe_name: &'static str,
}

pub fn uv_asset(os: Os, arch: &str) -> Result<UvAsset, String> {
    let (target, ext) = match (os, arch) {
        (Os::Linux, "x86_64") => ("x86_64-unknown-linux-gnu", "tar.gz"),
        (Os::Linux, "aarch64") => ("aarch64-unknown-linux-gnu", "tar.gz"),
        (Os::Windows, "x86_64") => ("x86_64-pc-windows-msvc", "zip"),
        (Os::Windows, "aarch64") => ("aarch64-pc-windows-msvc", "zip"),
        _ => return Err(format!("no uv release for architecture {arch}")),
    };
    let exe_name = if os == Os::Windows { "uv.exe" } else { "uv" };
    let inner = if ext == "zip" { exe_name.to_string() } else { format!("uv-{target}/{exe_name}") };
    Ok(UvAsset {
        url: format!("https://github.com/astral-sh/uv/releases/download/{UV_VERSION}/uv-{target}.{ext}"),
        ext,
        inner,
        exe_name,
    })
}

/// `hash_file` is `<hex>  <file name>`.
pub fn verify_sha256(bytes: &[u8], hash_file: &str) -> Result<(), String> {
    let want = hash_file.split_whitespace().next().unwrap_or("").to_lowercase();
    let got = hex::encode(Sha256::digest(bytes));
    if want == got {
        Ok(())
    } else {
        Err(format!("the uv download failed the SHA-256 check (expected {want}, got {got}); nothing was installed"))
    }
}

fn extract_uv(asset: &UvAsset, bytes: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    if asset.ext == "zip" {
        let mut z = zip::ZipArchive::new(std::io::Cursor::new(bytes)).map_err(|e| e.to_string())?;
        let mut f = z.by_name(&asset.inner).map_err(|e| e.to_string())?;
        f.read_to_end(&mut out).map_err(|e| e.to_string())?;
        return Ok(out);
    }
    let mut ar = tar::Archive::new(flate2::read::GzDecoder::new(bytes));
    for e in ar.entries().map_err(|e| e.to_string())? {
        let mut e = e.map_err(|e| e.to_string())?;
        let hit = e.path().map_err(|e| e.to_string())?.as_ref() == Path::new(&asset.inner);
        if hit {
            e.read_to_end(&mut out).map_err(|e| e.to_string())?;
            return Ok(out);
        }
    }
    Err(format!("{} not found in the uv archive", asset.inner))
}

// ------------------------------------------------------------------ Setup

pub struct Setup<'a> {
    pub os: Os,
    pub arch: String,
    pub paths: BridgePaths,
    /// The bundled bridge source (Tauri resource `bridge/`).
    pub resource_bridge: PathBuf,
    pub downloader: &'a dyn Downloader,
    pub runner: &'a dyn ProcessRunner,
    pub fs: &'a dyn Fs,
    pub os_step: &'a dyn OsStep,
    pub bridge: &'a dyn BridgeApi,
    pub report: &'a dyn Fn(Progress),
    pub wait: Duration,
    pub poll: Duration,
}

impl Setup<'_> {
    pub fn data_dir(&self) -> PathBuf {
        self.paths.venv.parent().map(Path::to_path_buf).unwrap_or_default()
    }

    /// What setup will change. Shown before anything is touched.
    pub fn change_list(&self) -> Vec<String> {
        let data = self.data_dir();
        let mut l = vec![
            format!("uv: found on PATH, else downloaded from GitHub Releases (SHA-256 checked) into {}", data.join("uv").display()),
            "Managed Python: downloaded by uv into its own cache; your system Python is not used".to_string(),
            format!("Bridge source: copied to {}", data.join("bridge").display()),
            format!("Virtual environment: {}", self.paths.venv.display()),
        ];
        l.extend(self.os_step.describe());
        l.push("Hermes Agent config: not changed".into());
        l
    }

    fn emit(&self, step: &str, state: &str, message: &str) {
        (self.report)(Progress { step: step.into(), state: state.into(), message: message.into() });
    }

    fn step<T>(&self, step: &str, message: &str, f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
        self.emit(step, "running", message);
        match f() {
            Ok(v) => {
                self.emit(step, "done", message);
                Ok(v)
            }
            Err(e) => {
                self.emit(step, "error", &e);
                Err(e)
            }
        }
    }

    fn ensure_uv(&self) -> Result<PathBuf, String> {
        if let Some(p) = self.fs.which("uv") {
            return Ok(p);
        }
        let asset = uv_asset(self.os, &self.arch)?;
        let dir = self.data_dir().join("uv");
        let exe = dir.join(asset.exe_name);
        if self.fs.exists(&exe) {
            return Ok(exe);
        }
        let archive = self.downloader.get(&asset.url)?;
        let hash = self.downloader.get(&format!("{}.sha256", asset.url))?;
        verify_sha256(&archive, &String::from_utf8_lossy(&hash))?;
        let bin = extract_uv(&asset, &archive)?;
        self.fs.create_dir_all(&dir)?;
        self.fs.write(&exe, &bin)?;
        self.fs.set_executable(&exe)?;
        Ok(exe)
    }

    fn sync(&self, uv: &Path) -> Result<(), String> {
        let src = self.data_dir().join("bridge");
        self.fs.copy_dir(&self.resource_bridge, &src, &COPY_SKIP)?;
        let args: Vec<String> = ["sync", "--frozen", "--no-dev"].iter().map(|s| s.to_string()).collect();
        let env = [("UV_PROJECT_ENVIRONMENT".to_string(), self.paths.venv.to_string_lossy().into_owned())];
        let out = self.runner.run_with(uv, &args, &env, Some(&src)).map_err(|e| format!("uv could not start: {e}"))?;
        if out.code != Some(0) {
            return Err(format!("uv sync failed ({:?}): {}", out.code, out.stderr.trim()));
        }
        Ok(())
    }

    fn wait_for_bridge(&self) -> bool {
        let start = Instant::now();
        loop {
            if self.bridge.status().is_ok() {
                return true;
            }
            if start.elapsed() >= self.wait {
                return false;
            }
            std::thread::sleep(self.poll);
        }
    }

    /// Run setup after "Continue". `update`: an app update, so the bridge is stopped first and started after.
    pub fn run(&self, update: bool) -> Result<(), String> {
        self.step("change-list", "Saving the list of changes", || {
            let text = self.change_list().join("\n") + "\n";
            self.fs.create_dir_all(&self.paths.config_dir)?;
            self.fs.write(&self.paths.config_dir.join("INSTALLED.txt"), text.as_bytes())
        })?;
        if update {
            self.step("stop", "Stopping the bridge", || self.os_step.stop_bridge())?;
        }
        let uv = self.step("uv", "Getting uv", || self.ensure_uv())?;
        self.step("sync", "Installing the bridge (uv sync)", || self.sync(&uv))?;
        self.step("os", "Setting up autostart", || self.os_step.apply())?;
        if update {
            self.step("start", "Starting the bridge", || self.os_step.start_bridge())?;
        }
        self.emit("wait", "running", "Waiting for the bridge to answer");
        if self.wait_for_bridge() {
            self.emit("wait", "done", "The bridge answers");
        } else {
            let msg = format!("warning: the bridge did not answer within {} s", self.wait.as_secs_f64());
            self.emit("wait", "done", &msg);
        }
        Ok(())
    }
}

// ------------------------------------------------------------------ real deps

pub struct UreqDownloader;

impl Downloader for UreqDownloader {
    fn get(&self, url: &str) -> Result<Vec<u8>, String> {
        let mut resp = ureq::get(url).call().map_err(|e| format!("download of {url} failed: {e}"))?;
        resp.body_mut()
            .with_config()
            .limit(200 * 1024 * 1024)
            .read_to_vec()
            .map_err(|e| format!("download of {url} failed: {e}"))
    }
}

pub struct RealFs;

/// `skip` applies to the top level of `from` only; nested folders are copied whole.
fn copy_tree(from: &Path, to: &Path, skip: &[&str]) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)? {
        let entry = entry?;
        let name = entry.file_name();
        if skip.iter().any(|s| name == *s) {
            continue;
        }
        let dest = to.join(&name);
        if entry.file_type()?.is_dir() {
            copy_tree(&entry.path(), &dest, &[])?;
        } else {
            std::fs::copy(entry.path(), dest)?;
        }
    }
    Ok(())
}

impl Fs for RealFs {
    fn create_dir_all(&self, p: &Path) -> Result<(), String> {
        std::fs::create_dir_all(p).map_err(|e| format!("{}: {e}", p.display()))
    }
    fn write(&self, p: &Path, bytes: &[u8]) -> Result<(), String> {
        std::fs::write(p, bytes).map_err(|e| format!("{}: {e}", p.display()))
    }
    fn exists(&self, p: &Path) -> bool {
        p.exists()
    }
    fn copy_dir(&self, from: &Path, to: &Path, skip: &[&str]) -> Result<(), String> {
        // Replace the destination so files removed from the source do not linger.
        if to.exists() {
            std::fs::remove_dir_all(to).map_err(|e| format!("removing {}: {e}", to.display()))?;
        }
        copy_tree(from, to, skip).map_err(|e| format!("copying {}: {e}", from.display()))
    }
    fn set_executable(&self, p: &Path) -> Result<(), String> {
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(p, std::fs::Permissions::from_mode(0o755)).map_err(|e| e.to_string())?;
        }
        let _ = p;
        Ok(())
    }
    fn which(&self, name: &str) -> Option<PathBuf> {
        let file = if cfg!(windows) { format!("{name}.exe") } else { name.to_string() };
        std::env::split_paths(&std::env::var_os("PATH")?).map(|d| d.join(&file)).find(|p| p.is_file())
    }
}

/// The bundled `bridge/` source: the Tauri resource dir, or the repo's `bridge/` in a dev build.
pub fn resource_bridge(resource_dir: Option<PathBuf>) -> PathBuf {
    resource_dir
        .map(|d| d.join("bridge"))
        .filter(|p| p.is_dir())
        .unwrap_or_else(|| Path::new(env!("CARGO_MANIFEST_DIR")).join("../../bridge"))
}

fn with_real<R>(resource: PathBuf, report: &dyn Fn(Progress), f: impl FnOnce(&Setup) -> R) -> R {
    let paths = BridgePaths::from_env();
    let bridge = LocalHttp::from_paths(&paths);
    #[cfg(target_os = "linux")]
    let os_step = crate::linux_service::real_os_step();
    #[cfg(not(target_os = "linux"))]
    let os_step = NoOsStep;
    let s = Setup {
        os: Os::current(),
        arch: std::env::consts::ARCH.to_string(),
        paths,
        resource_bridge: resource,
        downloader: &UreqDownloader,
        runner: &SystemRunner,
        fs: &RealFs,
        os_step: &os_step,
        bridge: &bridge,
        report,
        wait: BRIDGE_WAIT,
        poll: Duration::from_millis(500),
    };
    f(&s)
}

/// `needed` when the bridge executable is missing; `change_list` for the screen.
#[tauri::command]
pub async fn setup_state() -> Result<Value, String> {
    Ok(with_real(PathBuf::new(), &|_| {}, |s| {
        json!({"needed": !s.paths.exe.exists(), "change_list": s.change_list()})
    }))
}

#[tauri::command]
pub async fn setup_run(app: tauri::AppHandle, update: Option<bool>) -> Result<(), String> {
    use tauri::{Emitter, Manager};
    let resource = resource_bridge(app.path().resource_dir().ok());
    tauri::async_runtime::spawn_blocking(move || {
        let emit = |p: Progress| {
            let _ = app.emit("setup-progress", p);
        };
        with_real(resource, &emit, |s| s.run(update.unwrap_or(false)))
    })
    .await
    .map_err(|e| e.to_string())?
}

// ------------------------------------------------------------------ tests

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::{Answer, BridgeError, BridgeResult, CliOutput};
    use std::sync::{Arc, Mutex};

    type Log = Arc<Mutex<Vec<String>>>;

    struct FakeDl {
        log: Log,
        archive: Vec<u8>,
        hash: String,
    }
    impl Downloader for FakeDl {
        fn get(&self, url: &str) -> Result<Vec<u8>, String> {
            self.log.lock().unwrap().push(format!("get {url}"));
            Ok(if url.ends_with(".sha256") { self.hash.clone().into_bytes() } else { self.archive.clone() })
        }
    }

    struct FakeFs {
        log: Log,
        on_path: Option<PathBuf>,
    }
    impl Fs for FakeFs {
        fn create_dir_all(&self, p: &Path) -> Result<(), String> {
            self.log.lock().unwrap().push(format!("mkdir {}", p.display()));
            Ok(())
        }
        fn write(&self, p: &Path, bytes: &[u8]) -> Result<(), String> {
            self.log.lock().unwrap().push(format!("write {} {}", p.display(), String::from_utf8_lossy(bytes)));
            Ok(())
        }
        fn exists(&self, _p: &Path) -> bool {
            false
        }
        fn copy_dir(&self, from: &Path, to: &Path, skip: &[&str]) -> Result<(), String> {
            self.log.lock().unwrap().push(format!("copy {} {} {}", from.display(), to.display(), skip.join(",")));
            Ok(())
        }
        fn set_executable(&self, p: &Path) -> Result<(), String> {
            self.log.lock().unwrap().push(format!("chmod {}", p.display()));
            Ok(())
        }
        fn which(&self, _name: &str) -> Option<PathBuf> {
            self.on_path.clone()
        }
    }

    struct FakeRunner {
        log: Log,
        calls: Mutex<Vec<(PathBuf, Vec<String>, Vec<(String, String)>, Option<PathBuf>)>>,
    }
    impl ProcessRunner for FakeRunner {
        fn run_with(
            &self,
            exe: &Path,
            args: &[String],
            env: &[(String, String)],
            cwd: Option<&Path>,
        ) -> std::io::Result<CliOutput> {
            self.log.lock().unwrap().push("sync".into());
            self.calls.lock().unwrap().push((exe.to_path_buf(), args.to_vec(), env.to_vec(), cwd.map(Path::to_path_buf)));
            Ok(CliOutput { code: Some(0), stdout: String::new(), stderr: String::new() })
        }
    }

    struct FakeOs(Log);
    impl OsStep for FakeOs {
        fn describe(&self) -> Vec<String> {
            vec!["Autostart: a Scheduled Task".into(), "Firewall rule: optional, asks for UAC".into()]
        }
        fn apply(&self) -> Result<(), String> {
            self.0.lock().unwrap().push("apply".into());
            Ok(())
        }
        fn stop_bridge(&self) -> Result<(), String> {
            self.0.lock().unwrap().push("stop".into());
            Ok(())
        }
        fn start_bridge(&self) -> Result<(), String> {
            self.0.lock().unwrap().push("start".into());
            Ok(())
        }
    }

    struct FakeBridge(bool);
    impl BridgeApi for FakeBridge {
        fn status(&self) -> BridgeResult {
            if self.0 {
                Ok(Answer { via: "http", data: json!({}) })
            } else {
                Err(BridgeError::Unreachable("down".into()))
            }
        }
        fn devices(&self) -> BridgeResult { unreachable!() }
        fn pair(&self, _: &str, _: bool) -> BridgeResult { unreachable!() }
        fn revoke(&self, _: &str) -> BridgeResult { unreachable!() }
        fn trusted(&self) -> BridgeResult { unreachable!() }
        fn trust(&self) -> BridgeResult { unreachable!() }
        fn untrust(&self, _: &str) -> BridgeResult { unreachable!() }
        fn doctor(&self) -> BridgeResult { unreachable!() }
    }

    fn tar_gz_with_uv() -> Vec<u8> {
        let mut tar_bytes = Vec::new();
        {
            let mut b = tar::Builder::new(&mut tar_bytes);
            let data = b"UVBIN";
            let mut h = tar::Header::new_gnu();
            h.set_size(data.len() as u64);
            h.set_mode(0o755);
            h.set_cksum();
            b.append_data(&mut h, "uv-x86_64-unknown-linux-gnu/uv", &data[..]).unwrap();
            b.finish().unwrap();
        }
        let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        std::io::Write::write_all(&mut gz, &tar_bytes).unwrap();
        gz.finish().unwrap()
    }

    struct Rig {
        log: Log,
        dl: FakeDl,
        fs: FakeFs,
        runner: FakeRunner,
        os: FakeOs,
        events: Mutex<Vec<Progress>>,
    }

    fn rig(on_path: Option<&str>, good_hash: bool) -> Rig {
        let log: Log = Arc::new(Mutex::new(vec![]));
        let archive = tar_gz_with_uv();
        let hash = if good_hash {
            format!("{}  uv-x86_64-unknown-linux-gnu.tar.gz\n", hex::encode(Sha256::digest(&archive)))
        } else {
            "00".repeat(32)
        };
        Rig {
            dl: FakeDl { log: log.clone(), archive, hash },
            fs: FakeFs { log: log.clone(), on_path: on_path.map(PathBuf::from) },
            runner: FakeRunner { log: log.clone(), calls: Mutex::new(vec![]) },
            os: FakeOs(log.clone()),
            events: Mutex::new(vec![]),
            log,
        }
    }

    fn setup<'a>(r: &'a Rig, bridge: &'a FakeBridge, report: &'a dyn Fn(Progress)) -> Setup<'a> {
        Setup {
            os: Os::Linux,
            arch: "x86_64".into(),
            paths: BridgePaths::resolve(Os::Linux, &|k| (k == "HOME").then(|| "/home/u".to_string())),
            resource_bridge: PathBuf::from("/res/bridge"),
            downloader: &r.dl,
            runner: &r.runner,
            fs: &r.fs,
            os_step: &r.os,
            bridge,
            report,
            wait: Duration::from_millis(30),
            poll: Duration::from_millis(5),
        }
    }

    #[test]
    fn change_list_is_shown_before_any_change_and_saved_to_installed_txt() {
        let r = rig(Some("/usr/bin/uv"), true);
        let b = FakeBridge(true);
        let report = |p| r.events.lock().unwrap().push(p);
        let s = setup(&r, &b, &report);
        let list = s.change_list().join("\n");
        assert!(r.log.lock().unwrap().is_empty(), "listing must not change anything");
        for want in [
            "/home/u/.local/share/hermes-remote/uv",
            "Managed Python",
            "/home/u/.local/share/hermes-remote/venv",
            "Autostart: a Scheduled Task",
            "Firewall rule: optional, asks for UAC",
            "Hermes Agent config: not changed",
        ] {
            assert!(list.contains(want), "{want} missing from {list}");
        }
        s.run(false).unwrap();
        let log = r.log.lock().unwrap();
        let first_write = log.iter().find(|l| l.starts_with("write ")).unwrap();
        assert!(first_write.starts_with("write /home/u/.config/hermes-remote/INSTALLED.txt "));
        assert!(first_write.contains("Hermes Agent config: not changed"));
        assert_eq!(log.iter().position(|l| l == first_write), Some(1)); // after its mkdir, before every other change
    }

    #[test]
    fn uv_on_path_skips_the_download_otherwise_download_is_hash_checked() {
        let b = FakeBridge(true);
        let r = rig(Some("/usr/bin/uv"), true);
        let report = |_| {};
        setup(&r, &b, &report).run(false).unwrap();
        assert!(!r.log.lock().unwrap().iter().any(|l| l.starts_with("get ")));
        assert_eq!(r.runner.calls.lock().unwrap()[0].0, PathBuf::from("/usr/bin/uv"));

        let r = rig(None, true);
        setup(&r, &b, &report).run(false).unwrap();
        let log = r.log.lock().unwrap().clone();
        let url = "https://github.com/astral-sh/uv/releases/download/0.13.6/uv-x86_64-unknown-linux-gnu.tar.gz";
        assert!(log.contains(&format!("get {url}")));
        assert!(log.contains(&format!("get {url}.sha256")));
        assert!(log.contains(&"write /home/u/.local/share/hermes-remote/uv/uv UVBIN".to_string()));
        assert!(log.contains(&"chmod /home/u/.local/share/hermes-remote/uv/uv".to_string()));

        let r = rig(None, false);
        let err = setup(&r, &b, &report).run(false).unwrap_err();
        assert!(err.contains("SHA-256"), "{err}");
        let log = r.log.lock().unwrap();
        assert!(!log.iter().any(|l| l.contains("/uv/uv")) && !log.contains(&"sync".to_string()));

        assert!(verify_sha256(b"abc", "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD  x").is_ok());
        assert_eq!(
            uv_asset(Os::Linux, "x86_64").unwrap().url,
            format!("https://github.com/astral-sh/uv/releases/download/{UV_VERSION}/uv-x86_64-unknown-linux-gnu.tar.gz")
        );
        assert!(uv_asset(Os::Windows, "aarch64").unwrap().url.ends_with("uv-aarch64-pc-windows-msvc.zip"));
    }

    #[test]
    fn uv_sync_argv_and_env_are_exact() {
        let r = rig(Some("/usr/bin/uv"), true);
        let b = FakeBridge(true);
        let report = |_| {};
        setup(&r, &b, &report).run(false).unwrap();
        let calls = r.runner.calls.lock().unwrap();
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].1, ["sync", "--frozen", "--no-dev"]);
        assert_eq!(
            calls[0].2,
            [("UV_PROJECT_ENVIRONMENT".to_string(), "/home/u/.local/share/hermes-remote/venv".to_string())]
        );
        assert_eq!(calls[0].3, Some(PathBuf::from("/home/u/.local/share/hermes-remote/bridge")));
        let log = r.log.lock().unwrap();
        assert!(log.contains(
            &"copy /res/bridge /home/u/.local/share/hermes-remote/bridge .venv,__pycache__,.pytest_cache,tests".to_string()
        ));
    }

    #[test]
    fn setup_dirs_sit_under_xdg_data_home() {
        let r = rig(None, true);
        let b = FakeBridge(true);
        let report = |_| {};
        let mut s = setup(&r, &b, &report);
        s.paths = BridgePaths::resolve(Os::Linux, &|k| match k {
            "HOME" => Some("/home/u".to_string()),
            "XDG_DATA_HOME" => Some("/d".to_string()),
            _ => None,
        });
        s.run(false).unwrap();
        let log = r.log.lock().unwrap();
        assert!(log.contains(&"write /d/hermes-remote/uv/uv UVBIN".to_string()));
        assert!(log.iter().any(|l| l.starts_with("copy /res/bridge /d/hermes-remote/bridge ")));
        assert_eq!(r.runner.calls.lock().unwrap()[0].3, Some(PathBuf::from("/d/hermes-remote/bridge")));
    }

    #[test]
    fn recopy_removes_stale_files_and_skips_tests_only_at_the_top() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("src");
        let dst = dir.path().join("dst");
        std::fs::create_dir_all(src.join("tests")).unwrap();
        std::fs::create_dir_all(src.join("pkg/tests")).unwrap();
        std::fs::write(src.join("tests/top.py"), "x").unwrap();
        std::fs::write(src.join("pkg/tests/nested.py"), "x").unwrap();
        std::fs::write(src.join("keep.py"), "x").unwrap();
        std::fs::create_dir_all(&dst).unwrap();
        std::fs::write(dst.join("stale.py"), "x").unwrap();
        RealFs.copy_dir(&src, &dst, &COPY_SKIP).unwrap();
        assert!(!dst.join("stale.py").exists());
        assert!(dst.join("keep.py").exists());
        assert!(dst.join("pkg/tests/nested.py").exists());
        assert!(!dst.join("tests").exists());
    }

    #[test]
    fn wait_warning_names_the_configured_wait() {
        let r = rig(Some("/usr/bin/uv"), true);
        let b = FakeBridge(false);
        let report = |p| r.events.lock().unwrap().push(p);
        setup(&r, &b, &report).run(false).unwrap();
        let ev = r.events.lock().unwrap();
        assert_eq!(ev.last().unwrap().message, "warning: the bridge did not answer within 0.03 s");
    }

    #[test]
    fn update_stops_the_bridge_before_syncing_and_starts_it_after() {
        let r = rig(Some("/usr/bin/uv"), true);
        let b = FakeBridge(false);
        let report = |p| r.events.lock().unwrap().push(p);
        setup(&r, &b, &report).run(true).unwrap();
        let log = r.log.lock().unwrap();
        let ops: Vec<&str> =
            log.iter().map(String::as_str).filter(|l| ["stop", "sync", "apply", "start"].contains(l)).collect();
        assert_eq!(ops, ["stop", "sync", "apply", "start"]);
        // a bridge that never answers is a warning, not an error
        let ev = r.events.lock().unwrap();
        let last = ev.last().unwrap();
        assert_eq!((last.step.as_str(), last.state.as_str()), ("wait", "done"));
        assert!(last.message.starts_with("warning"));
    }
}
