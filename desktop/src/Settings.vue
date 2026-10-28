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
import { ACCENTS, applyAccent, savedAccent } from "./theme.js";

defineEmits(["setup"]);
const settings = ref({ start_at_logon: false, terminal_shell: "" });
const linger = ref(false);
const runMode = ref("always-on");
const installed = ref(null);
const checks = ref([]);
const pending = ref(false);
const firewall = ref("");
const error = ref("");
const accent = ref(savedAccent());
const dark =
  globalThis.matchMedia?.("(prefers-color-scheme: light)")?.matches !== true;
function pickAccent(id) {
  accent.value = id;
  applyAccent(id);
}

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
    runMode.value =
      (await getRunMode()) === "app-only" ? "app-only" : "always-on";
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
  <div class="settings stack">
    <p v-if="error" class="error" data-test="error">{{ error }}</p>

    <div class="card stack">
      <h2>Appearance</h2>
      <div class="swatches" role="radiogroup" aria-label="Accent colour">
        <button
          v-for="(a, id) in ACCENTS"
          :key="id"
          class="swatch"
          :class="{ selected: accent === id }"
          role="radio"
          :aria-checked="accent === id"
          :title="a[0]"
          :data-accent="id"
          :style="{ background: dark ? a[1] : a[2] }"
          @click="pickAccent(id)"
        ></button>
      </div>
    </div>

    <div class="card stack">
      <h2>Doctor</h2>
      <div class="row">
        <button
          data-test="doctor"
          class="primary"
          :disabled="pending"
          @click="doctor"
        >
          Run doctor
        </button>
        <span v-if="pending" data-test="doctor-pending" class="muted"
          >Checking…</span
        >
      </div>
      <ul class="plain status-rows" v-if="checks.length">
        <li
          v-for="c in checks"
          :key="c.id"
          data-test="check"
          :class="`level-${c.level}`"
        >
          {{ c.message }}
        </li>
      </ul>
    </div>

    <div class="card stack">
      <h2>Firewall</h2>
      <div class="row">
        <button data-test="fw-check" @click="checkFirewall">Check</button>
        <button data-test="fw-fix" @click="guard(firewallFix)">Fix</button>
        <span
          v-if="firewall"
          data-test="fw-state"
          :class="firewall === 'open' ? 'level-ok' : 'level-warn'"
          >{{ firewall }}</span
        >
      </div>
    </div>

    <div class="card stack">
      <h2>General</h2>
      <label v-if="settings.platform !== 'windows'" class="field">
        Run mode
        <select data-test="run-mode" :value="runMode" @change="changeRunMode">
          <option value="always-on">Always on (systemd service)</option>
          <option value="app-only">Only while the app is open</option>
        </select>
      </label>
      <p
        v-if="settings.platform !== 'windows' && runMode === 'app-only'"
        data-test="no-sandbox"
        class="muted small"
        style="margin: 0"
      >
        In this mode the bridge runs without systemd sandboxing.
      </p>
      <label class="check">
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
      <p
        v-if="settings.platform === 'windows'"
        data-test="crash-note"
        class="muted small"
        style="margin: 0"
      >
        If the app crashes the bridge stops too; start Hermes Remote from the
        Start menu again.
      </p>
      <!-- Linux only (D33): Windows has no "keep running" mode, the app owns the bridge (D34). -->
      <label
        v-if="settings.start_at_logon && settings.platform !== 'windows'"
        class="check"
      >
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
      <label class="field">
        Terminal shell (empty = default)
        <input
          data-test="shell"
          :value="settings.terminal_shell ?? ''"
          @change="guard(() => setTerminalShell($event.target.value))"
        />
      </label>

      <div class="row">
        <button data-test="open-logs" @click="guard(openLogs)">
          Open log folder
        </button>
      </div>
    </div>

    <div class="card stack">
      <h2>What this install changed</h2>
      <pre v-if="installed" data-test="installed">{{ installed }}</pre>
      <p v-else class="muted" style="margin: 0">Setup has not run yet</p>
      <div class="row">
        <button data-test="rerun-setup" @click="$emit('setup')">
          Run setup again
        </button>
      </div>
    </div>
  </div>
</template>
