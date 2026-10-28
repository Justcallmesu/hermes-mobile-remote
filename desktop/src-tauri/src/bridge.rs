//! Bridge client: the app always gets an answer about the bridge.
//! `LocalHttp` (the running bridge's `/v1/local/*`) first, `CliJson` (the venv's CLI) second;
//! `Fallback` tags answers that came from the CLI with `via: "cli"`.

use serde::Serialize;
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::time::Duration;

pub const DEFAULT_PORT: u16 = 8650;
pub const LOCAL_HEADER: &str = "X-Hermes-Local";
pub const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Debug, Clone, PartialEq)]
pub enum BridgeError {
    /// Connection refused, timeout or any transport failure: the bridge is not answering.
    Unreachable(String),
    /// 403 (wrong or missing local secret).
    Forbidden(String),
    Http { status: u16, body: String },
    Cli { code: Option<i32>, stderr: String },
}

impl std::fmt::Display for BridgeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            BridgeError::Unreachable(m) => write!(f, "bridge unreachable: {m}"),
            BridgeError::Forbidden(m) => write!(f, "bridge refused the local secret: {m}"),
            BridgeError::Http { status, body } => write!(f, "bridge answered {status}: {body}"),
            BridgeError::Cli { code, stderr } => write!(f, "bridge CLI failed ({code:?}): {stderr}"),
        }
    }
}

/// A bridge answer and where it came from (`"http"` or `"cli"`).
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Answer {
    pub via: &'static str,
    pub data: Value,
}

pub type BridgeResult = Result<Answer, BridgeError>;

pub trait BridgeApi {
    fn status(&self) -> BridgeResult;
    fn devices(&self) -> BridgeResult;
    fn pair(&self, name: &str, replace: bool) -> BridgeResult;
    fn revoke(&self, name: &str) -> BridgeResult;
    /// The trusted-networks list.
    fn trusted(&self) -> BridgeResult;
    /// Trust the network this PC is on now.
    fn trust(&self) -> BridgeResult;
    fn untrust(&self, id: &str) -> BridgeResult;
    fn doctor(&self) -> BridgeResult;
}

// ------------------------------------------------------------------ paths

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Os {
    Windows,
    Linux,
}

impl Os {
    pub fn current() -> Os {
        if cfg!(windows) {
            Os::Windows
        } else {
            Os::Linux
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct BridgePaths {
    pub config_dir: PathBuf,
    pub venv: PathBuf,
    pub exe: PathBuf,
}

impl BridgePaths {
    /// `env` looks up an environment variable.
    pub fn resolve(os: Os, env: &dyn Fn(&str) -> Option<String>) -> BridgePaths {
        let get = |k: &str| env(k).filter(|v| !v.is_empty());
        match os {
            Os::Windows => {
                let base = PathBuf::from(get("LOCALAPPDATA").unwrap_or_default()).join("hermes-remote");
                let venv = base.join("venv");
                BridgePaths {
                    config_dir: base.join("config"),
                    exe: venv.join("Scripts").join("hermes-remote-bridge.exe"),
                    venv,
                }
            }
            Os::Linux => {
                let home = PathBuf::from(get("HOME").unwrap_or_default());
                let config = match get("XDG_CONFIG_HOME") {
                    Some(x) => PathBuf::from(x),
                    None => home.join(".config"),
                };
                let data = match get("XDG_DATA_HOME") {
                    Some(x) => PathBuf::from(x),
                    None => home.join(".local/share"),
                };
                let venv = data.join("hermes-remote/venv");
                BridgePaths {
                    config_dir: config.join("hermes-remote"),
                    exe: venv.join("bin").join("hermes-remote-bridge"),
                    venv,
                }
            }
        }
    }

    pub fn from_env() -> BridgePaths {
        BridgePaths::resolve(Os::current(), &|k| std::env::var(k).ok())
    }

    pub fn secret_file(&self) -> PathBuf {
        self.config_dir.join("local-secret")
    }

    /// `port = N` from the bridge `config.toml`, else 8650.
    pub fn port(&self) -> u16 {
        std::fs::read_to_string(self.config_dir.join("config.toml"))
            .ok()
            .and_then(|t| config_value(&t, "port")?.parse().ok())
            .unwrap_or(DEFAULT_PORT)
    }
}

/// The value of the `key = value` line in a bridge `config.toml` text: comment stripped, trimmed, unquoted.
pub fn config_value(config_text: &str, key: &str) -> Option<String> {
    config_text.lines().find_map(|l| {
        let (k, v) = l.split_once('=')?;
        (k.trim() == key).then(|| v.split('#').next().unwrap_or("").trim().trim_matches('"').to_string())
    })
}

// ------------------------------------------------------------------ LocalHttp

pub struct LocalHttp {
    base: String,
    secret_file: PathBuf,
    timeout: Duration,
}

impl LocalHttp {
    pub fn new(port: u16, secret_file: PathBuf) -> Self {
        LocalHttp { base: format!("http://127.0.0.1:{port}/v1/local"), secret_file, timeout: Duration::from_secs(2) }
    }

    pub fn from_paths(paths: &BridgePaths) -> Self {
        Self::new(paths.port(), paths.secret_file())
    }

    pub fn with_timeout(mut self, t: Duration) -> Self {
        self.timeout = t;
        self
    }

    fn call(&self, method: &str, route: &str, body: Option<Value>) -> BridgeResult {
        let secret = std::fs::read_to_string(&self.secret_file)
            .map_err(|e| BridgeError::Forbidden(format!("local secret unreadable: {e}")))?;
        let agent: ureq::Agent = ureq::Agent::config_builder()
            .timeout_global(Some(self.timeout))
            .http_status_as_error(false)
            .build()
            .into();
        let url = format!("{}/{route}", self.base);
        let sent = if method == "GET" {
            agent.get(&url).header(LOCAL_HEADER, secret.trim()).call()
        } else {
            agent
                .post(&url)
                .header(LOCAL_HEADER, secret.trim())
                .send_json(body.unwrap_or_else(|| json!({})))
        };
        let mut resp = sent.map_err(|e| BridgeError::Unreachable(e.to_string()))?;
        let status = resp.status().as_u16();
        if status == 403 {
            return Err(BridgeError::Forbidden(resp.body_mut().read_to_string().unwrap_or_default()));
        }
        if !(200..300).contains(&status) {
            return Err(BridgeError::Http { status, body: resp.body_mut().read_to_string().unwrap_or_default() });
        }
        let data: Value = resp.body_mut().read_json().map_err(|e| BridgeError::Unreachable(e.to_string()))?;
        Ok(Answer { via: "http", data })
    }
}

impl BridgeApi for LocalHttp {
    fn status(&self) -> BridgeResult {
        self.call("GET", "status", None)
    }
    fn devices(&self) -> BridgeResult {
        self.call("GET", "devices", None)
    }
    fn pair(&self, name: &str, replace: bool) -> BridgeResult {
        self.call("POST", "pair", Some(json!({"name": name, "replace": replace})))
    }
    fn revoke(&self, name: &str) -> BridgeResult {
        self.call("POST", "revoke", Some(json!({"name": name})))
    }
    fn trusted(&self) -> BridgeResult {
        self.call("GET", "trust", None)
    }
    fn trust(&self) -> BridgeResult {
        self.call("POST", "trust", None)
    }
    fn untrust(&self, id: &str) -> BridgeResult {
        self.call("POST", "untrust", Some(json!({"id": id})))
    }
    fn doctor(&self) -> BridgeResult {
        self.call("GET", "doctor", None)
    }
}

// ------------------------------------------------------------------ CliJson

pub struct CliOutput {
    pub code: Option<i32>,
    pub stdout: String,
    pub stderr: String,
}

pub trait ProcessRunner {
    /// Run `exe args` with extra environment variables, in `cwd` when given.
    fn run_with(
        &self,
        exe: &Path,
        args: &[String],
        env: &[(String, String)],
        cwd: Option<&Path>,
    ) -> std::io::Result<CliOutput>;

    fn run(&self, exe: &Path, args: &[String]) -> std::io::Result<CliOutput> {
        self.run_with(exe, args, &[], None)
    }
}

/// Process creation flags for the bridge CLI: no console window on Windows.
pub fn creation_flags(os: Os) -> u32 {
    if os == Os::Windows {
        CREATE_NO_WINDOW
    } else {
        0
    }
}

/// Variables an AppImage runtime exports that point into its own mount. A child (uv, the
/// bridge's Python, systemctl) must not inherit them, or it loads the bundle's libraries and Python.
pub const APPIMAGE_LEAKS: [&str; 6] =
    ["PYTHONHOME", "PYTHONPATH", "LD_LIBRARY_PATH", "GTK_PATH", "GIO_MODULE_DIR", "GDK_PIXBUF_MODULE_FILE"];

/// Remove the AppImage's leaked variables from `cmd` (only when running from an AppImage).
pub fn scrub_appimage_env(cmd: &mut std::process::Command, in_appimage: bool) {
    if in_appimage {
        for k in APPIMAGE_LEAKS {
            cmd.env_remove(k);
        }
    }
}

pub fn in_appimage() -> bool {
    std::env::var_os("APPIMAGE").is_some()
}

/// Tells the bridge not to show its own "trust this network?" prompt; the app shows one (D44).
pub const NO_PROMPT_ENV: &str = "HERMES_REMOTE_NO_TRUST_PROMPT";

pub struct SystemRunner;

impl ProcessRunner for SystemRunner {
    fn run_with(
        &self,
        exe: &Path,
        args: &[String],
        env: &[(String, String)],
        cwd: Option<&Path>,
    ) -> std::io::Result<CliOutput> {
        let mut cmd = std::process::Command::new(exe);
        scrub_appimage_env(&mut cmd, in_appimage());
        cmd.args(args).envs(env.iter().map(|(k, v)| (k, v)));
        if let Some(c) = cwd {
            cmd.current_dir(c);
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(creation_flags(Os::Windows));
        }
        let out = cmd.output()?;
        Ok(CliOutput {
            code: out.status.code(),
            stdout: String::from_utf8_lossy(&out.stdout).into_owned(),
            stderr: String::from_utf8_lossy(&out.stderr).into_owned(),
        })
    }
}

pub struct CliJson {
    exe: PathBuf,
    runner: Box<dyn ProcessRunner + Send + Sync>,
}

impl CliJson {
    pub fn new(exe: PathBuf, runner: Box<dyn ProcessRunner + Send + Sync>) -> Self {
        CliJson { exe, runner }
    }

    fn run(&self, args: &[&str], json_out: bool) -> BridgeResult {
        let argv: Vec<String> = args.iter().map(|s| s.to_string()).collect();
        let out = self
            .runner
            .run(&self.exe, &argv)
            .map_err(|e| BridgeError::Cli { code: None, stderr: e.to_string() })?;
        if out.code != Some(0) {
            return Err(BridgeError::Cli { code: out.code, stderr: out.stderr });
        }
        let data = if json_out {
            serde_json::from_str(&out.stdout)
                .map_err(|e| BridgeError::Cli { code: out.code, stderr: format!("bad JSON: {e}") })?
        } else {
            json!({"output": out.stdout})
        };
        Ok(Answer { via: "cli", data })
    }
}

impl BridgeApi for CliJson {
    /// The CLI has no status command; the bridge not answering is the status.
    fn status(&self) -> BridgeResult {
        Ok(Answer { via: "cli", data: json!({"running": false}) })
    }
    fn devices(&self) -> BridgeResult {
        self.run(&["devices", "--json"], true)
    }
    fn pair(&self, name: &str, replace: bool) -> BridgeResult {
        let mut a = vec!["pair", name];
        if replace {
            a.push("--replace");
        }
        self.run(&a, false)
    }
    fn revoke(&self, name: &str) -> BridgeResult {
        self.run(&["revoke", name], false)
    }
    fn trusted(&self) -> BridgeResult {
        self.run(&["trust", "--list", "--json"], true)
    }
    fn trust(&self) -> BridgeResult {
        self.run(&["trust"], false)
    }
    fn untrust(&self, id: &str) -> BridgeResult {
        self.run(&["trust", "--remove", id], false)
    }
    fn doctor(&self) -> BridgeResult {
        self.run(&["doctor", "--json"], true)
    }
}

// ------------------------------------------------------------------ Fallback

pub struct Fallback {
    first: Box<dyn BridgeApi + Send + Sync>,
    second: Box<dyn BridgeApi + Send + Sync>,
}

impl Fallback {
    pub fn new(first: Box<dyn BridgeApi + Send + Sync>, second: Box<dyn BridgeApi + Send + Sync>) -> Self {
        Fallback { first, second }
    }

    pub fn real() -> Self {
        let paths = BridgePaths::from_env();
        Fallback::new(
            Box::new(LocalHttp::from_paths(&paths)),
            Box::new(CliJson::new(paths.exe.clone(), Box::new(SystemRunner))),
        )
    }

    fn either(&self, f: impl Fn(&dyn BridgeApi) -> BridgeResult) -> BridgeResult {
        match f(self.first.as_ref()) {
            Err(BridgeError::Unreachable(_)) | Err(BridgeError::Forbidden(_)) => f(self.second.as_ref()),
            other => other,
        }
    }
}

impl BridgeApi for Fallback {
    fn status(&self) -> BridgeResult {
        self.either(|a| a.status())
    }
    fn devices(&self) -> BridgeResult {
        self.either(|a| a.devices())
    }
    fn pair(&self, name: &str, replace: bool) -> BridgeResult {
        self.either(|a| a.pair(name, replace))
    }
    fn revoke(&self, name: &str) -> BridgeResult {
        self.either(|a| a.revoke(name))
    }
    fn trusted(&self) -> BridgeResult {
        self.either(|a| a.trusted())
    }
    fn trust(&self) -> BridgeResult {
        self.either(|a| a.trust())
    }
    fn untrust(&self, id: &str) -> BridgeResult {
        self.either(|a| a.untrust(id))
    }
    fn doctor(&self) -> BridgeResult {
        self.either(|a| a.doctor())
    }
}

// ------------------------------------------------------------------ Tauri commands

fn map(r: BridgeResult) -> Result<Answer, String> {
    r.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn bridge_status() -> Result<Answer, String> {
    map(Fallback::real().status())
}
#[tauri::command]
pub async fn bridge_devices() -> Result<Answer, String> {
    map(Fallback::real().devices())
}
#[tauri::command]
pub async fn bridge_pair(name: String, replace: Option<bool>) -> Result<Answer, String> {
    map(Fallback::real().pair(&name, replace.unwrap_or(false)))
}
#[tauri::command]
pub async fn bridge_revoke(name: String) -> Result<Answer, String> {
    map(Fallback::real().revoke(&name))
}
#[tauri::command]
pub async fn bridge_trusted() -> Result<Answer, String> {
    map(Fallback::real().trusted())
}
#[tauri::command]
pub async fn bridge_trust() -> Result<Answer, String> {
    map(Fallback::real().trust())
}
#[tauri::command]
pub async fn bridge_untrust(id: String) -> Result<Answer, String> {
    map(Fallback::real().untrust(&id))
}
#[tauri::command]
pub async fn bridge_doctor() -> Result<Answer, String> {
    map(Fallback::real().doctor())
}

// ------------------------------------------------------------------ tests

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::{Arc, Mutex};

    /// One-shot-per-request fake HTTP server; records each raw request head + body.
    fn fake_server(status: &'static str, body: &'static str) -> (u16, Arc<Mutex<Vec<String>>>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let log = seen.clone();
        std::thread::spawn(move || {
            for stream in listener.incoming() {
                let mut s = stream.unwrap();
                let mut buf = Vec::new();
                let mut chunk = [0u8; 1024];
                loop {
                    let n = s.read(&mut chunk).unwrap();
                    buf.extend_from_slice(&chunk[..n]);
                    let text = String::from_utf8_lossy(&buf).to_string();
                    if let Some(i) = text.find("\r\n\r\n") {
                        let len = text[..i]
                            .to_lowercase()
                            .lines()
                            .find_map(|l| l.strip_prefix("content-length:").map(|v| v.trim().parse::<usize>().unwrap()))
                            .unwrap_or(0);
                        if buf.len() >= i + 4 + len {
                            break;
                        }
                    }
                    if n == 0 {
                        break;
                    }
                }
                log.lock().unwrap().push(String::from_utf8_lossy(&buf).to_string());
                let resp = format!(
                    "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                s.write_all(resp.as_bytes()).unwrap();
            }
        });
        (port, seen)
    }

    fn secret_in(dir: &tempfile::TempDir) -> PathBuf {
        let p = dir.path().join("local-secret");
        std::fs::write(&p, "s3cret\n").unwrap();
        p
    }

    #[test]
    fn local_http_sends_secret_and_parses_each_route() {
        let dir = tempfile::tempdir().unwrap();
        let (port, seen) = fake_server("200 OK", r#"{"ok":1}"#);
        let api = LocalHttp::new(port, secret_in(&dir));
        let want = Answer { via: "http", data: json!({"ok": 1}) };
        assert_eq!(api.status().unwrap(), want);
        assert_eq!(api.devices().unwrap(), want);
        assert_eq!(api.pair("phone", true).unwrap(), want);
        assert_eq!(api.revoke("phone").unwrap(), want);
        assert_eq!(api.trusted().unwrap(), want);
        assert_eq!(api.trust().unwrap(), want);
        assert_eq!(api.untrust("n1").unwrap(), want);
        assert_eq!(api.doctor().unwrap(), want);
        let seen = seen.lock().unwrap();
        let heads: Vec<&str> = seen.iter().map(|r| r.lines().next().unwrap()).collect();
        assert_eq!(
            heads,
            [
                "GET /v1/local/status HTTP/1.1",
                "GET /v1/local/devices HTTP/1.1",
                "POST /v1/local/pair HTTP/1.1",
                "POST /v1/local/revoke HTTP/1.1",
                "GET /v1/local/trust HTTP/1.1",
                "POST /v1/local/trust HTTP/1.1",
                "POST /v1/local/untrust HTTP/1.1",
                "GET /v1/local/doctor HTTP/1.1",
            ]
        );
        for r in seen.iter() {
            assert!(r.to_lowercase().contains("x-hermes-local: s3cret\r\n"), "{r}");
        }
        let body = |i: usize| -> Value { serde_json::from_str(seen[i].split("\r\n\r\n").nth(1).unwrap()).unwrap() };
        assert_eq!(body(2), json!({"name": "phone", "replace": true}));
        assert_eq!(body(6), json!({"id": "n1"}));
    }

    struct FakeRunner {
        calls: Mutex<Vec<(PathBuf, Vec<String>)>>,
        out: String,
    }

    impl ProcessRunner for Arc<FakeRunner> {
        fn run_with(
            &self,
            exe: &Path,
            args: &[String],
            _env: &[(String, String)],
            _cwd: Option<&Path>,
        ) -> std::io::Result<CliOutput> {
            self.calls.lock().unwrap().push((exe.to_path_buf(), args.to_vec()));
            Ok(CliOutput { code: Some(0), stdout: self.out.clone(), stderr: String::new() })
        }
    }

    fn cli(out: &str) -> (CliJson, Arc<FakeRunner>) {
        let r = Arc::new(FakeRunner { calls: Mutex::new(vec![]), out: out.into() });
        (CliJson::new(PathBuf::from("/v/hrb"), Box::new(r.clone())), r)
    }

    #[test]
    fn fallback_uses_cli_on_refused_timeout_and_403() {
        let dir = tempfile::tempdir().unwrap();
        // refused: grab a free port, then close it
        let free = TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        // timeout: accepts, never answers
        let hang = TcpListener::bind("127.0.0.1:0").unwrap();
        let hang_port = hang.local_addr().unwrap().port();
        std::thread::spawn(move || {
            let _held: Vec<_> = hang.incoming().collect();
        });
        let (forbidden_port, _) = fake_server("403 Forbidden", r#"{"error":"forbidden_local"}"#);
        let cases = [
            LocalHttp::new(free, secret_in(&dir)),
            LocalHttp::new(hang_port, secret_in(&dir)).with_timeout(Duration::from_millis(300)),
            LocalHttp::new(forbidden_port, secret_in(&dir)),
        ];
        for http in cases {
            let (cli, runner) = cli(r#"{"checks":{}}"#);
            let fb = Fallback::new(Box::new(http), Box::new(cli));
            let a = fb.doctor().unwrap();
            assert_eq!(a, Answer { via: "cli", data: json!({"checks": {}}) });
            assert_eq!(runner.calls.lock().unwrap().len(), 1);
        }
        // a healthy bridge is tagged http and the CLI is never run
        let (port, _) = fake_server("200 OK", r#"{"running":true}"#);
        let (cli, runner) = cli("{}");
        let fb = Fallback::new(Box::new(LocalHttp::new(port, secret_in(&dir))), Box::new(cli));
        assert_eq!(fb.status().unwrap().via, "http");
        assert!(runner.calls.lock().unwrap().is_empty());
    }

    #[test]
    fn cli_argv_is_exact_and_windows_hides_the_console() {
        let (cli, runner) = cli("{}");
        cli.devices().unwrap();
        cli.trusted().unwrap();
        cli.doctor().unwrap();
        cli.pair("phone", false).unwrap();
        cli.pair("tab", true).unwrap();
        cli.revoke("phone").unwrap();
        cli.untrust("n1").unwrap();
        let argv: Vec<Vec<String>> = runner.calls.lock().unwrap().iter().map(|c| c.1.clone()).collect();
        let want: Vec<Vec<&str>> = vec![
            vec!["devices", "--json"],
            vec!["trust", "--list", "--json"],
            vec!["doctor", "--json"],
            vec!["pair", "phone"],
            vec!["pair", "tab", "--replace"],
            vec!["revoke", "phone"],
            vec!["trust", "--remove", "n1"],
        ];
        assert_eq!(argv, want);
        assert_eq!(runner.calls.lock().unwrap()[0].0, PathBuf::from("/v/hrb"));
        assert_eq!(creation_flags(Os::Windows), 0x0800_0000);
        assert_eq!(creation_flags(Os::Linux), 0);
    }

    fn env_of(pairs: &[(&str, &str)]) -> impl Fn(&str) -> Option<String> {
        let m: Vec<(String, String)> = pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        move |k| m.iter().find(|(a, _)| a == k).map(|(_, v)| v.clone())
    }

    #[test]
    fn paths_resolve_per_os_with_env_overrides() {
        let p = BridgePaths::resolve(Os::Linux, &env_of(&[("HOME", "/home/u")]));
        assert_eq!(p.config_dir, PathBuf::from("/home/u/.config/hermes-remote"));
        assert_eq!(p.venv, PathBuf::from("/home/u/.local/share/hermes-remote/venv"));
        assert_eq!(p.exe, PathBuf::from("/home/u/.local/share/hermes-remote/venv/bin/hermes-remote-bridge"));
        let p = BridgePaths::resolve(Os::Linux, &env_of(&[("HOME", "/home/u"), ("XDG_CONFIG_HOME", "/x")]));
        assert_eq!(p.config_dir, PathBuf::from("/x/hermes-remote"));
        let base = PathBuf::from("C:\\L").join("hermes-remote");
        let p = BridgePaths::resolve(Os::Windows, &env_of(&[("LOCALAPPDATA", "C:\\L")]));
        assert_eq!(p.config_dir, base.join("config"));
        assert_eq!(p.venv, base.join("venv"));
        assert_eq!(p.exe, base.join("venv").join("Scripts").join("hermes-remote-bridge.exe"));
        assert_eq!(p.secret_file(), base.join("config").join("local-secret"));
    }

    #[test]
    fn linux_data_dir_honours_xdg_data_home_when_set_and_not_empty() {
        let p = BridgePaths::resolve(Os::Linux, &env_of(&[("HOME", "/home/u"), ("XDG_DATA_HOME", "/d")]));
        assert_eq!(p.venv, PathBuf::from("/d/hermes-remote/venv"));
        assert_eq!(p.exe, PathBuf::from("/d/hermes-remote/venv/bin/hermes-remote-bridge"));
        let p = BridgePaths::resolve(Os::Linux, &env_of(&[("HOME", "/home/u"), ("XDG_DATA_HOME", "")]));
        assert_eq!(p.venv, PathBuf::from("/home/u/.local/share/hermes-remote/venv"));
    }

    #[test]
    fn appimage_leaked_env_is_removed_from_children() {
        let mut cmd = std::process::Command::new("x");
        scrub_appimage_env(&mut cmd, true);
        let removed: Vec<_> = cmd.get_envs().filter(|(_, v)| v.is_none()).map(|(k, _)| k.to_owned()).collect();
        for k in APPIMAGE_LEAKS {
            assert!(removed.iter().any(|r| r == k), "{k} not removed");
        }
        let mut plain = std::process::Command::new("x");
        scrub_appimage_env(&mut plain, false);
        assert_eq!(plain.get_envs().count(), 0);
    }
}
