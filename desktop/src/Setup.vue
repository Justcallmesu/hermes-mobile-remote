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
  stop?.();
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
  <div class="setup card stack">
    <h2>Set up Hermes Remote</h2>
    <template v-if="!running && !finished && !steps.length">
      <p style="margin: 0">Setup will change:</p>
      <ul data-test="change-list" class="changes">
        <li v-for="c in changes" :key="c" data-test="change">{{ c }}</li>
      </ul>
      <div class="row">
        <button data-test="continue" class="primary" @click="cont">
          Continue
        </button>
      </div>
    </template>
    <ul class="plain steps">
      <li
        v-for="s in steps"
        :key="s.step"
        data-test="step"
        :class="`state-${s.state}`"
      >
        <span class="badge">{{ s.state }}</span
        ><span class="sr">:</span> {{ s.message }}
      </li>
    </ul>
    <p v-if="error" class="error" data-test="error">{{ error }}</p>
    <div class="row">
      <button
        v-if="error && steps.length"
        data-test="retry"
        class="primary"
        @click="cont"
      >
        Try again
      </button>
      <button
        v-if="finished"
        data-test="finish"
        class="primary"
        @click="emit('done')"
      >
        Open the app
      </button>
    </div>
  </div>
</template>
