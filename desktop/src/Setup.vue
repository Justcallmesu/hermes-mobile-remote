<script setup>
import { ref, onMounted, onUnmounted } from "vue";
import { setupState, setupRun, onSetupProgress } from "./api.js";

const emit = defineEmits(["done"]);
const changes = ref([]);
const update = ref(false);
const steps = ref([]);
const running = ref(false);
const finished = ref(false);
const error = ref("");
let stop = null;

function progress(p) {
  const i = steps.value.findIndex((s) => s.step === p.step);
  if (i >= 0) steps.value[i] = p;
  else steps.value.push(p);
}

async function cont() {
  running.value = true;
  error.value = "";
  steps.value = [];
  stop = await onSetupProgress(progress);
  try {
    await setupRun(update.value);
    finished.value = true;
  } catch (e) {
    error.value = String(e?.message ?? e);
  }
  running.value = false;
}

onMounted(async () => {
  try {
    const s = await setupState();
    changes.value = s?.change_list ?? [];
    update.value = s ? !s.needed : false;
  } catch (e) {
    error.value = String(e?.message ?? e);
  }
});
onUnmounted(() => stop?.());
</script>

<template>
  <div class="setup">
    <h2>Set up Hermes Remote</h2>
    <template v-if="!running && !finished && !steps.length">
      <p>Setup will change:</p>
      <ul data-test="change-list">
        <li v-for="c in changes" :key="c" data-test="change">{{ c }}</li>
      </ul>
      <button data-test="continue" @click="cont">Continue</button>
    </template>
    <ul class="plain">
      <li v-for="s in steps" :key="s.step" data-test="step" :class="`state-${s.state}`">
        {{ s.state }}: {{ s.message }}
      </li>
    </ul>
    <p v-if="error" class="error" data-test="error">{{ error }}</p>
    <button v-if="error && steps.length" data-test="retry" @click="cont">Try again</button>
    <button v-if="finished" data-test="finish" @click="emit('done')">Open the app</button>
  </div>
</template>
