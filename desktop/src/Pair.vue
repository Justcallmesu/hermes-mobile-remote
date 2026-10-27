<script setup>
import { ref } from "vue";
import { bridgePair } from "./api.js";
import PairResult from "./PairResult.vue";

const name = ref("");
const result = ref(null);
const error = ref("");

async function go() {
  error.value = "";
  result.value = null;
  try {
    result.value = await bridgePair(name.value.trim(), false);
  } catch (e) {
    error.value = String(e?.message ?? e);
  }
}
</script>

<template>
  <div class="pair">
    <label>
      Device name
      <input v-model="name" data-test="name" placeholder="phone" />
    </label>
    <button data-test="pair-go" :disabled="!name.trim()" @click="go">Pair</button>
    <p v-if="error" class="error" data-test="error">{{ error }}</p>
    <PairResult v-if="result" :answer="result" />
  </div>
</template>
