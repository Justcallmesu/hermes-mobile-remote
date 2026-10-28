<script setup>
import { ref, computed, onMounted } from "vue";
import { bridgeStatus, bridgeStart, bridgeStop, bridgeTrust } from "./api.js";

const answer = ref(null);
const error = ref("");

async function refresh() {
  try {
    answer.value = await bridgeStatus();
  } catch (e) {
    error.value = String(e?.message ?? e);
  }
}

const data = computed(() => answer.value?.data ?? {});
const running = computed(() => data.value.running === true);
const offline = computed(() => answer.value?.via === "cli");
const listen = computed(() => data.value.listen ?? []);
const lan = computed(() =>
  listen.value.find((h) =>
    /^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)/.test(h),
  ),
);
const tailnet = computed(() => listen.value.find((h) => /^100\./.test(h)));
const net = computed(() => data.value.network);

async function toggle() {
  error.value = "";
  try {
    await (running.value ? bridgeStop() : bridgeStart());
  } catch (e) {
    error.value = String(e?.message ?? e);
  }
  await refresh();
}

async function trust() {
  error.value = "";
  try {
    await bridgeTrust();
  } catch (e) {
    error.value = String(e?.message ?? e);
  }
  await refresh();
}

onMounted(refresh);
defineEmits(["pair"]);
</script>

<template>
  <div class="home stack">
    <div class="card hero">
      <span
        class="dot"
        :class="running ? 'ok' : offline ? 'fail' : ''"
        aria-hidden="true"
      ></span>
      <div>
        <div class="title">Bridge {{ running ? "on" : "off" }}</div>
        <div class="muted small">
          {{
            running
              ? `Listening on port ${data.port ?? ""}`
              : "Phones cannot reach this PC"
          }}
        </div>
      </div>
      <span class="spacer"></span>
      <label class="switch" title="Start or stop the bridge">
        <input
          type="checkbox"
          data-test="power"
          :checked="running"
          @click.prevent="toggle"
        />
        <span class="track"></span>
      </label>
    </div>
    <p v-if="offline" class="notice">bridge not running, showing saved data</p>
    <p v-if="error" class="error" data-test="error">{{ error }}</p>
    <div v-if="answer" class="card">
      <h2>Status</h2>
      <ul data-test="status-line" class="status-rows">
        <li>
          <span
            class="dot"
            :class="net ? (net.trusted ? 'ok' : 'warn') : ''"
          ></span>
          <span class="k">Network<span class="sr">:&#32;</span></span>
          <span class="v">{{
            net
              ? `${net.name} (${net.trusted ? "trusted" : "untrusted"})`
              : "unknown"
          }}</span>
        </li>
        <li>
          <span
            class="dot"
            :class="
              data.firewall_ok === false ? 'fail' : data.firewall_ok ? 'ok' : ''
            "
          ></span>
          <span class="k">Firewall<span class="sr">:&#32;</span></span>
          <span class="v">{{
            data.firewall_ok === false
              ? "blocked"
              : data.firewall_ok
                ? "open"
                : "unknown"
          }}</span>
        </li>
        <li>
          <span class="dot" :class="lan ? 'ok' : ''"></span>
          <span class="k">LAN<span class="sr">:&#32;</span></span>
          <span class="v">{{ lan ?? "not reachable" }}</span>
        </li>
        <li>
          <span class="dot" :class="tailnet ? 'ok' : ''"></span>
          <span class="k">Tailnet<span class="sr">:&#32;</span></span>
          <span class="v">{{ tailnet ?? "not reachable" }}</span>
        </li>
      </ul>
    </div>
    <div
      v-if="running && net && !net.trusted"
      class="card row"
      data-test="trust-card"
    >
      <div style="flex: 1">
        <strong>Use this Wi-Fi without Tailscale?</strong>
        <div class="muted small">
          Only for networks you control, like home or office.
        </div>
      </div>
      <button class="primary" data-test="trust" @click="trust">
        Trust {{ net.name }}
      </button>
    </div>
    <div class="row">
      <button class="primary" @click="$emit('pair')">Pair a device</button>
      <button class="ghost" @click="refresh">Refresh</button>
    </div>
  </div>
</template>
