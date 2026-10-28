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
  ["pair", "Pair"],
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
      <div class="logo" aria-hidden="true">H</div>
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
      <Home v-if="tab === 'home'" @pair="tab = 'pair'" />
      <Devices v-else-if="tab === 'devices'" />
      <Pair v-else-if="tab === 'pair'" />
      <Settings v-else @setup="showSetup = true" />
    </section>
  </main>
</template>
