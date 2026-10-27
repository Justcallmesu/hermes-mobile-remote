import { describe, it, expect, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";

let answers;
const invoke = vi.fn(async (cmd, args) => {
  const a = answers[cmd];
  if (a instanceof Error) throw a;
  return typeof a === "function" ? a(args) : a ?? null;
});
vi.mock("@tauri-apps/api/core", () => ({ invoke: (...x) => invoke(...x) }));

let progressHandler;
vi.mock("@tauri-apps/api/event", () => ({
  listen: async (_name, h) => {
    progressHandler = h;
    return () => {};
  },
}));

import App from "./App.vue";

const running = {
  via: "http",
  data: {
    running: true,
    port: 8650,
    listen: ["127.0.0.1", "192.168.1.5", "100.64.0.2"],
    network: { id: "n1", name: "Home", trusted: true },
    firewall_ok: true,
  },
};

async function open(tab) {
  const w = mount(App);
  await flushPromises();
  if (tab) {
    await w.find(`[data-nav="${tab}"]`).trigger("click");
    await flushPromises();
  }
  return w;
}

beforeEach(() => {
  invoke.mockClear();
  answers = {
    app_version: "0.1.0",
    bridge_status: running,
    bridge_devices: { via: "http", data: { devices: [{ id: "d1", name: "phone", active: true }] } },
    bridge_pair: { via: "http", data: { code: "hermesremote://pair?x=1", qr_png_base64: "QUJD" } },
    bridge_revoke: { via: "http", data: {} },
    bridge_doctor: { via: "http", data: { checks: [{ id: "hermes", level: "ok", message: "Hermes found" }] } },
    get_settings: { start_at_logon: false, terminal_shell: null },
    get_installed_list: null,
  };
});

describe("Home", () => {
  it("switch calls stop when running and start when stopped, then reflects status", async () => {
    const w = await open();
    const sw = w.find('[data-test="power"]');
    expect(sw.element.checked).toBe(true);
    answers.bridge_status = { via: "http", data: { running: false } };
    await sw.trigger("click");
    await flushPromises();
    expect(invoke).toHaveBeenCalledWith("bridge_stop");
    expect(w.find('[data-test="power"]').element.checked).toBe(false);
    answers.bridge_status = running;
    await w.find('[data-test="power"]').trigger("click");
    await flushPromises();
    expect(invoke).toHaveBeenCalledWith("bridge_start");
    expect(w.find('[data-test="power"]').element.checked).toBe(true);
  });

  it("shows an unsupported control error inline", async () => {
    answers.bridge_stop = new Error("not available yet on this platform");
    const w = await open();
    await w.find('[data-test="power"]').trigger("click");
    await flushPromises();
    expect(w.find('[data-test="error"]').text()).toContain("not available yet on this platform");
  });

  it("status line shows trusted network and each reachability path", async () => {
    const w = await open();
    const t = w.find('[data-test="status-line"]').text();
    expect(t).toContain("Home");
    expect(t).toContain("trusted");
    expect(t).toContain("Firewall: open");
    expect(t).toContain("LAN: 192.168.1.5");
    expect(t).toContain("Tailnet: 100.64.0.2");
    answers.bridge_status = {
      via: "http",
      data: { running: true, listen: ["127.0.0.1"], network: { name: "Cafe", trusted: false }, firewall_ok: false },
    };
    const w2 = await open();
    const t2 = w2.find('[data-test="status-line"]').text();
    expect(t2).toContain("untrusted");
    expect(t2).toContain("Firewall: blocked");
    expect(t2).toContain("LAN: not reachable");
    expect(t2).toContain("Tailnet: not reachable");
  });

  it("via cli shows the bridge-not-running notice", async () => {
    answers.bridge_status = { via: "cli", data: { running: false } };
    const w = await open();
    expect(w.text()).toContain("bridge not running, showing saved data");
    expect(w.find('[data-test="power"]').element.checked).toBe(false);
  });
});

describe("Devices", () => {
  it("lists devices; revoke asks to confirm then calls revoke", async () => {
    const w = await open("devices");
    expect(w.text()).toContain("phone");
    await w.find('[data-test="revoke"]').trigger("click");
    await flushPromises();
    expect(invoke).not.toHaveBeenCalledWith("bridge_revoke", expect.anything());
    await w.find('[data-test="confirm-revoke"]').trigger("click");
    await flushPromises();
    expect(invoke).toHaveBeenCalledWith("bridge_revoke", { name: "phone" });
  });

  it("re-pair warns the old code stops working", async () => {
    const w = await open("devices");
    await w.find('[data-test="repair"]').trigger("click");
    expect(w.text()).toContain("old code stops working");
    await w.find('[data-test="confirm-repair"]').trigger("click");
    await flushPromises();
    expect(invoke).toHaveBeenCalledWith("bridge_pair", { name: "phone", replace: true });
    expect(w.find('[data-test="qr"]').exists()).toBe(true);
  });
});

describe("Pair", () => {
  it("shows the QR image and copies the code", async () => {
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    const w = await open("pair");
    await w.find('[data-test="name"]').setValue("tablet");
    await w.find('[data-test="pair-go"]').trigger("click");
    await flushPromises();
    expect(invoke).toHaveBeenCalledWith("bridge_pair", { name: "tablet", replace: false });
    expect(w.find('[data-test="qr"]').attributes("src")).toBe("data:image/png;base64,QUJD");
    await w.find('[data-test="copy"]').trigger("click");
    expect(writeText).toHaveBeenCalledWith("hermesremote://pair?x=1");
  });
});

describe("Setup", () => {
  it("lists the changes, waits for Continue, then shows each step and the error", async () => {
    answers.setup_state = { needed: true, change_list: ["Virtual environment: /v", "Hermes Agent config: not changed"] };
    answers.setup_run = () => {
      const send = (step, state, message) => progressHandler({ payload: { step, state, message } });
      send("uv", "running", "Getting uv");
      send("uv", "done", "Getting uv");
      send("sync", "running", "Installing the bridge");
      send("sync", "error", "uv sync failed: boom");
      throw new Error("uv sync failed: boom");
    };
    const w = await open();
    expect(w.findAll('[data-test="change"]').map((c) => c.text())).toEqual([
      "Virtual environment: /v",
      "Hermes Agent config: not changed",
    ]);
    expect(invoke).not.toHaveBeenCalledWith("setup_run", expect.anything());
    await w.find('[data-test="continue"]').trigger("click");
    await flushPromises();
    expect(invoke).toHaveBeenCalledWith("setup_run", { update: false });
    expect(w.findAll('[data-test="step"]').map((s) => s.text())).toEqual([
      "done: Getting uv",
      "error: uv sync failed: boom",
    ]);
    expect(w.find('[data-test="error"]').text()).toContain("uv sync failed: boom");
  });
});

describe("Settings", () => {
  it("shows pending while doctor runs, then the coloured results", async () => {
    let release;
    answers.bridge_doctor = () => new Promise((r) => (release = () => r(running_doctor())));
    const running_doctor = () => ({
      via: "http",
      data: { checks: [{ id: "hermes", level: "ok", message: "Hermes found" }, { id: "firewall", level: "fail", message: "Port blocked" }] },
    });
    const w = await open("settings");
    await w.find('[data-test="doctor"]').trigger("click");
    await flushPromises();
    expect(w.find('[data-test="doctor-pending"]').exists()).toBe(true);
    release();
    await flushPromises();
    expect(w.find('[data-test="doctor-pending"]').exists()).toBe(false);
    const rows = w.findAll('[data-test="check"]');
    expect(rows.map((r) => r.text())).toEqual(["Hermes found", "Port blocked"]);
    expect(rows[1].classes()).toContain("level-fail");
  });

  it("shows the install list or that setup has not run", async () => {
    let w = await open("settings");
    expect(w.text()).toContain("Setup has not run yet");
    answers.get_installed_list = "a venv\n";
    w = await open("settings");
    expect(w.text()).toContain("a venv");
  });

  it("settings actions call their commands and show errors inline", async () => {
    answers.firewall_fix = new Error("not available yet on this platform");
    const w = await open("settings");
    await w.find('[data-test="open-logs"]').trigger("click");
    await w.find('[data-test="logon"]').setValue(true);
    await w.find('[data-test="shell"]').setValue("zsh");
    await w.find('[data-test="shell"]').trigger("change");
    await w.find('[data-test="fw-fix"]').trigger("click");
    await flushPromises();
    expect(invoke).toHaveBeenCalledWith("open_logs");
    expect(invoke).toHaveBeenCalledWith("set_start_at_logon", { on: true });
    expect(invoke).toHaveBeenCalledWith("set_terminal_shell", { value: "zsh" });
    expect(w.find('[data-test="error"]').text()).toContain("not available yet");
  });
});
