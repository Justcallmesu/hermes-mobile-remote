<script setup>
import { ref, computed, onMounted } from "vue";
import { bridgeStatus, bridgeStart, bridgeStop } from "./api.js";

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
const lan = computed(() => listen.value.find((h) => /^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)/.test(h)));
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

onMounted(refresh);
</script>

<template>
  <div class="home">
    <label class="power">
      <input type="checkbox" data-test="power" :checked="running" @click.prevent="toggle" />
      Bridge {{ running ? "on" : "off" }}
    </label>
    <p v-if="offline" class="notice">bridge not running, showing saved data</p>
    <p v-if="error" class="error" data-test="error">{{ error }}</p>
    <p v-if="answer" data-test="status-line" class="status-line">
      <span v-if="net">Network: {{ net.name }} ({{ net.trusted ? "trusted" : "untrusted" }})</span>
      <span v-else>Network: unknown</span>
      · <span>Firewall: {{ data.firewall_ok === false ? "blocked" : data.firewall_ok ? "open" : "unknown" }}</span>
      · <span>LAN: {{ lan ?? "not reachable" }}</span>
      · <span>Tailnet: {{ tailnet ?? "not reachable" }}</span>
    </p>
  </div>
</template>
