// The one module that talks to the Rust side. Components import from here;
// tests mock "@tauri-apps/api/core".
import { invoke } from "@tauri-apps/api/core";

export function appVersion() {
  return invoke("app_version");
}

// Bridge data: each answers {via: "http"|"cli", data}.
export const bridgeStatus = () => invoke("bridge_status");
export const bridgeDevices = () => invoke("bridge_devices");
export const bridgePair = (name, replace = false) => invoke("bridge_pair", { name, replace });
export const bridgeRevoke = (name) => invoke("bridge_revoke", { name });
export const bridgeTrusted = () => invoke("bridge_trusted");
export const bridgeTrust = () => invoke("bridge_trust");
export const bridgeUntrust = (id) => invoke("bridge_untrust", { id });
export const bridgeDoctor = () => invoke("bridge_doctor");

// Controls (an OS backend may answer "not available yet on this platform").
export const bridgeStart = () => invoke("bridge_start");
export const bridgeStop = () => invoke("bridge_stop");
export const getSettings = () => invoke("get_settings");
export const setStartAtLogon = (on) => invoke("set_start_at_logon", { on });
export const getLinger = () => invoke("get_linger");
export const setLinger = (on) => invoke("set_linger", { on });
export const firewallFix = () => invoke("firewall_fix");
export const openLogs = () => invoke("open_logs");
export const setTerminalShell = (value) => invoke("set_terminal_shell", { value });
export const getInstalledList = () => invoke("get_installed_list");

// First-run setup: state {needed, change_list}; run emits "setup-progress" {step, state, message}.
export const setupState = () => invoke("setup_state");
export const setupRun = (update = false) => invoke("setup_run", { update });
export async function onSetupProgress(cb) {
  const { listen } = await import("@tauri-apps/api/event");
  return listen("setup-progress", (e) => cb(e.payload));
}
