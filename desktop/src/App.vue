<script setup>
import { ref, onMounted, onUnmounted } from "vue";
import { appVersion, setupState, setupRun } from "./api.js";
import Setup from "./Setup.vue";
import UpdateBanner from "./UpdateBanner.vue";
import Home from "./Home.vue";
import Devices from "./Devices.vue";
import Pair from "./Pair.vue";
import Settings from "./Settings.vue";

const version = ref("");
const tab = ref("home");
const tabs = [
  ["home", "Home"],
  ["devices", "Devices"],
  ["pair", "Pair a device"],
  ["settings", "Settings"],
];
const showSetup = ref(false);
// The tray menu (Rust) dispatches this DOM event: `new CustomEvent("navigate", { detail: "pair" })`.
function onNavigate(e) {
  if (tabs.some(([id]) => id === e.detail)) tab.value = e.detail;
}
onUnmounted(() => window.removeEventListener("navigate", onNavigate));
onMounted(async () => {
  window.addEventListener("navigate", onNavigate);
  version.value = await appVersion();
  try {
    const st = await setupState();
    showSetup.value = !!st?.needed;
    // An app update left the marker: re-sync the venv (the command removes the marker).
    if (!st?.needed && st?.resync) await setupRun(true);
  } catch {
    showSetup.value = false;
  }
});
</script>

<template>
  <main class="app">
    <header>
      <h1>Hermes Remote</h1>
      <p class="version">{{ version }}</p>
    </header>
    <UpdateBanner v-if="!showSetup" />
    <Setup v-if="showSetup" @done="showSetup = false" />
    <nav v-else>
      <button
        v-for="[id, label] in tabs"
        :key="id"
        :data-nav="id"
        :class="{ active: tab === id }"
        @click="tab = id"
      >
        {{ label }}
      </button>
    </nav>
    <section v-if="!showSetup">
      <Home v-if="tab === 'home'" />
      <Devices v-else-if="tab === 'devices'" />
      <Pair v-else-if="tab === 'pair'" />
      <Settings v-else @setup="showSetup = true" />
    </section>
  </main>
</template>

<style>
:root {
  color-scheme: light dark;
  --ok: #1a8a3a;
  --warn: #b7791f;
  --fail: #c0392b;
  font-family: system-ui, sans-serif;
}
body {
  margin: 0;
}
.app {
  box-sizing: border-box;
  min-width: 320px;
  max-width: 720px;
  min-height: 100vh;
  margin: 0 auto;
  padding: 12px;
}
.app header {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.app h1 {
  font-size: 1.1rem;
  margin: 0;
}
.version {
  opacity: 0.6;
  margin: 0;
  font-size: 0.8rem;
}
nav {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin: 8px 0;
}
nav button.active {
  font-weight: 600;
  text-decoration: underline;
}
.error {
  color: var(--fail);
}
.notice {
  color: var(--warn);
}
.level-ok {
  color: var(--ok);
}
.level-warn {
  color: var(--warn);
}
.level-fail {
  color: var(--fail);
}
.qr {
  width: 100%;
  max-width: 260px;
  image-rendering: pixelated;
}
ul.plain {
  list-style: none;
  padding: 0;
}
</style>
