// The one module that talks to the Rust side. Components import from here;
// tests mock "@tauri-apps/api/core".
import { invoke } from "@tauri-apps/api/core";

export function appVersion() {
  return invoke("app_version");
}
