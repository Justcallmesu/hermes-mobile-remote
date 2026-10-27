<script setup>
import { ref, onMounted } from "vue";
import { appVersion } from "./api.js";

const version = ref("");
onMounted(async () => {
  version.value = await appVersion();
});
</script>

<template>
  <main>
    <h1>Hermes Remote</h1>
    <p class="version">{{ version }}</p>
  </main>
</template>
