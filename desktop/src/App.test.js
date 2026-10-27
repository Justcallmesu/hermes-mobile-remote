import { describe, it, expect, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(async (cmd) => (cmd === "app_version" ? "0.1.0" : null)),
}));

import App from "./App.vue";

describe("App", () => {
  it("shows the title and the version from Rust", async () => {
    const w = mount(App);
    await flushPromises();
    expect(w.text()).toContain("Hermes Remote");
    expect(w.find(".version").text()).toBe("0.1.0");
  });
});
