<script setup>
import { ref, onMounted } from "vue";
import {
  bridgeDoctor,
  bridgeStatus,
  getSettings,
  setStartAtLogon,
  setTerminalShell,
  firewallFix,
  openLogs,
  getInstalledList,
} from "./api.js";

defineEmits(["setup"]);
const settings = ref({ start_at_logon: false, terminal_shell: "" });
const installed = ref(null);
const checks = ref([]);
const pending = ref(false);
const firewall = ref("");
const error = ref("");

// Run an action; a failure (e.g. "not available yet on this platform") shows inline.
async function guard(fn) {
  error.value = "";
  try {
    return await fn();
  } catch (e) {
    error.value = String(e?.message ?? e);
  }
}

async function doctor() {
  pending.value = true;
  checks.value = [];
  await guard(async () => {
    checks.value = (await bridgeDoctor()).data?.checks ?? [];
  });
  pending.value = false;
}

async function checkFirewall() {
  await guard(async () => {
    const ok = (await bridgeStatus()).data?.firewall_ok;
    firewall.value = ok === false ? "blocked" : ok ? "open" : "unknown";
  });
}

onMounted(async () => {
  await guard(async () => {
    settings.value = { ...settings.value, ...(await getSettings()) };
    installed.value = await getInstalledList();
  });
});
</script>

<template>
  <div class="settings">
    <p v-if="error" class="error" data-test="error">{{ error }}</p>

    <h2>Doctor</h2>
    <button data-test="doctor" :disabled="pending" @click="doctor">Run doctor</button>
    <p v-if="pending" data-test="doctor-pending">Checking…</p>
    <ul class="plain">
      <li v-for="c in checks" :key="c.id" data-test="check" :class="`level-${c.level}`">{{ c.message }}</li>
    </ul>

    <h2>Firewall</h2>
    <button data-test="fw-check" @click="checkFirewall">Check</button>
    <button data-test="fw-fix" @click="guard(firewallFix)">Fix</button>
    <span v-if="firewall" data-test="fw-state">{{ firewall }}</span>

    <h2>General</h2>
    <button data-test="open-logs" @click="guard(openLogs)">Open log folder</button>
    <label>
      <input
        type="checkbox"
        data-test="logon"
        :checked="settings.start_at_logon"
        @change="guard(() => setStartAtLogon($event.target.checked))"
      />
      Start at logon
    </label>
    <label>
      Terminal shell
      <input
        data-test="shell"
        :value="settings.terminal_shell ?? ''"
        @change="guard(() => setTerminalShell($event.target.value))"
      />
    </label>

    <h2>What this install changed</h2>
    <pre v-if="installed" data-test="installed">{{ installed }}</pre>
    <p v-else>Setup has not run yet</p>
    <button data-test="rerun-setup" @click="$emit('setup')">Run setup again</button>
  </div>
</template>
