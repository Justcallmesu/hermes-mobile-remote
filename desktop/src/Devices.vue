<script setup>
import { ref, computed, onMounted } from "vue";
import { bridgeDevices, bridgeRevoke, bridgePair } from "./api.js";
import PairResult from "./PairResult.vue";

const answer = ref(null);
const error = ref("");
const asking = ref(null); // {name, action: "revoke" | "repair"}
const paired = ref(null);

const devices = computed(() => (answer.value?.data?.devices ?? []).filter((d) => d.active));
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
  <div class="devices">
    <p v-if="offline" class="notice">bridge not running, showing saved data</p>
    <p v-if="error" class="error" data-test="error">{{ error }}</p>
    <p v-if="answer && !devices.length">No paired devices.</p>
    <ul class="plain">
      <li v-for="d in devices" :key="d.id" data-test="device">
        <strong>{{ d.name }}</strong>
        <button data-test="repair" @click="asking = { name: d.name, action: 'repair' }">Re-pair</button>
        <button data-test="revoke" @click="asking = { name: d.name, action: 'revoke' }">Revoke</button>
      </li>
    </ul>
    <div v-if="asking" class="confirm">
      <p v-if="asking.action === 'revoke'">Revoke {{ asking.name }}? It will be signed out.</p>
      <p v-else class="notice">
        Re-pairing {{ asking.name }}: the old code stops working. The phone must scan the new one.
      </p>
      <button v-if="asking.action === 'revoke'" data-test="confirm-revoke" @click="confirm">Yes, revoke</button>
      <button v-else data-test="confirm-repair" @click="confirm">Re-pair</button>
      <button data-test="cancel" @click="asking = null">Cancel</button>
    </div>
    <PairResult v-if="paired" :answer="paired" />
  </div>
</template>
