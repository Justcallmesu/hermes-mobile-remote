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
  getLinger,
  setLinger,
  getRunMode,
  setRunMode,
} from "./api.js";

defineEmits(["setup"]);
const settings = ref({ start_at_logon: false, terminal_shell: "" });
const linger = ref(false);
const runMode = ref("always-on");
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
    linger.value = (await getLinger()) === true;
    runMode.value = (await getRunMode()) === "app-only" ? "app-only" : "always-on";
  });
});

async function changeRunMode(event) {
  const mode = event.target.value;
  await guard(async () => {
    try {
      await setRunMode(mode);
      runMode.value = mode;
    } catch (e) {
      event.target.value = runMode.value;
      throw e;
    }
  });
}
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
      Run mode
      <select data-test="run-mode" :value="runMode" @change="changeRunMode">
        <option value="always-on">Always on (systemd service)</option>
        <option value="app-only">Only while the app is open</option>
      </select>
    </label>
    <p v-if="runMode === 'app-only'" data-test="no-sandbox">
      In this mode the bridge runs without systemd sandboxing.
    </p>
    <label>
      <input
        type="checkbox"
        data-test="logon"
        :disabled="runMode === 'app-only'"
        :checked="settings.start_at_logon"
        @change="
          guard(async () => {
            const on = $event.target.checked;
            await setStartAtLogon(on);
            settings.start_at_logon = on;
          })
        "
      />
      Start at logon
    </label>
    <p v-if="settings.platform === 'windows'" data-test="crash-note">
      If the app crashes the bridge stops too; start Hermes Remote from the Start menu again.
    </p>
    <label v-if="settings.start_at_logon">
      <input
        type="checkbox"
        data-test="linger"
        :disabled="runMode === 'app-only'"
        :checked="linger"
        @change="
          guard(async () => {
            const on = $event.target.checked;
            try {
              await setLinger(on);
              linger = on;
            } catch (e) {
              $event.target.checked = linger;
              throw e;
            }
          })
        "
      />
      Keep running after logout
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
