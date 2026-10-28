<script setup>
import { ref, onMounted } from "vue";
import { updateCheck, updateInstall, openUrl } from "./api.js";

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
    <a
      v-if="info.kind === 'link'"
      :href="info.url"
      data-test="update-link"
      @click.prevent="openUrl(info.url)"
      >View release</a
    >
    <button
      v-else
      class="primary"
      :disabled="busy"
      data-test="update-restart"
      @click="restart"
    >
      Restart to update
    </button>
    <span v-if="error" class="error" data-test="update-error">{{ error }}</span>
  </aside>
</template>
