<script setup>
// What a pairing answer shows: the QR inline with the code and a Copy button.
// HTTP answers carry `code` and `qr_png_base64`; CLI answers only `output` text.
import { computed } from "vue";

const props = defineProps({ answer: { type: Object, required: true } });
const data = computed(() => props.answer.data ?? {});

function copy() {
  return navigator.clipboard.writeText(data.value.code);
}
</script>

<template>
  <div class="pair-result">
    <template v-if="data.code">
      <img
        v-if="data.qr_png_base64"
        class="qr"
        data-test="qr"
        alt="Pairing QR code"
        :src="`data:image/png;base64,${data.qr_png_base64}`"
      />
      <code class="code" data-test="code">{{ data.code }}</code>
      <button data-test="copy" @click="copy">Copy</button>
    </template>
    <pre v-else data-test="output">{{ data.output }}</pre>
  </div>
</template>
