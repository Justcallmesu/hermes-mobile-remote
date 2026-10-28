<script setup>
import { ref, onMounted } from "vue";
import { updateCheck, updateInstall } from "./api.js";

const info = ref(null);
const error = ref("");
const busy = ref(false);

onMounted(async () => {
  try {
    info.value = (await updateCheck()) || null;
  } catch {
    info.value = null;
  }
});

async function restart() {
  busy.value = true;
  error.value = "";
  try {
    await updateInstall();
  } catch (e) {
    error.value = String(e?.message ?? e);
    busy.value = false;
  }
}
</script>

<template>
  <aside v-if="info" class="update-banner" data-test="update-banner">
    <span>New version {{ info.version }} available</span>
    <a v-if="info.kind === 'link'" :href="info.url" target="_blank" rel="noopener" data-test="update-link"
      >View release</a
    >
    <button v-else :disabled="busy" data-test="update-restart" @click="restart">Restart to update</button>
    <span v-if="error" class="error" data-test="update-error">{{ error }}</span>
  </aside>
</template>

<style>
.update-banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 8px 0;
  padding: 6px 8px;
  border: 1px solid var(--warn);
  border-radius: 4px;
}
</style>
