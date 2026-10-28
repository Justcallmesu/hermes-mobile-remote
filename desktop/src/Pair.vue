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
  <div class="pair stack">
    <div class="card stack">
      <h2>Pair a device</h2>
      <p class="muted small" style="margin: 0">
        Give the phone a name, then scan the code with the Hermes Remote app.
      </p>
      <div class="row">
        <label class="field" style="flex: 1">
          Device name
          <input
            v-model="name"
            data-test="name"
            placeholder="phone"
            @keyup.enter="name.trim() && go()"
          />
        </label>
        <button
          data-test="pair-go"
          class="primary"
          style="align-self: flex-end"
          :disabled="!name.trim()"
          @click="go"
        >
          Pair
        </button>
      </div>
      <p v-if="error" class="error" data-test="error">{{ error }}</p>
    </div>
    <div v-if="result" class="card"><PairResult :answer="result" /></div>
  </div>
</template>
