<script setup>
import { ref, computed, onMounted } from "vue";
import { bridgeDevices, bridgeRevoke, bridgePair } from "./api.js";
import PairResult from "./PairResult.vue";

const answer = ref(null);
const error = ref("");
const asking = ref(null); // {name, action: "revoke" | "repair"}
const paired = ref(null);

const devices = computed(() =>
  (answer.value?.data?.devices ?? []).filter((d) => d.active),
);
const offline = computed(() => answer.value?.via === "cli");

async function refresh() {
  try {
    answer.value = await bridgeDevices();
  } catch (e) {
    error.value = String(e?.message ?? e);
  }
}

async function confirm() {
  const { name, action } = asking.value;
  asking.value = null;
  error.value = "";
  try {
    if (action === "revoke") {
      await bridgeRevoke(name);
      paired.value = null;
      await refresh();
    } else {
      paired.value = await bridgePair(name, true);
    }
  } catch (e) {
    error.value = String(e?.message ?? e);
  }
}

onMounted(refresh);
</script>

<template>
  <div class="devices stack">
    <p v-if="offline" class="notice">bridge not running, showing saved data</p>
    <p v-if="error" class="error" data-test="error">{{ error }}</p>
    <div class="card">
      <h2>Paired devices</h2>
      <p v-if="answer && !devices.length" class="empty">No paired devices.</p>
      <ul class="plain">
        <li v-for="d in devices" :key="d.id" data-test="device" class="device">
          <span class="avatar" aria-hidden="true">{{
            (d.name || "?")[0].toUpperCase()
          }}</span>
          <strong>{{ d.name }}</strong>
          <span class="spacer"></span>
          <button
            data-test="repair"
            @click="asking = { name: d.name, action: 'repair' }"
          >
            Re-pair
          </button>
          <button
            data-test="revoke"
            class="danger"
            @click="asking = { name: d.name, action: 'revoke' }"
          >
            Revoke
          </button>
        </li>
      </ul>
    </div>
    <div v-if="asking" class="card confirm stack">
      <p v-if="asking.action === 'revoke'" style="margin: 0">
        Revoke {{ asking.name }}? It will be signed out.
      </p>
      <p v-else class="notice">
        Re-pairing {{ asking.name }}: the old code stops working. The phone must
        scan the new one.
      </p>
      <div class="row">
        <button
          v-if="asking.action === 'revoke'"
          data-test="confirm-revoke"
          class="danger solid"
          @click="confirm"
        >
          Yes, revoke
        </button>
        <button
          v-else
          data-test="confirm-repair"
          class="primary"
          @click="confirm"
        >
          Re-pair
        </button>
        <button data-test="cancel" class="ghost" @click="asking = null">
          Cancel
        </button>
      </div>
    </div>
    <div v-if="paired" class="card"><PairResult :answer="paired" /></div>
  </div>
</template>
