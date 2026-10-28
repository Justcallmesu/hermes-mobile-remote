import { createApp } from "vue";
import App from "./App.vue";
import "./style.css";
import { applyAccent, savedAccent } from "./theme.js";

applyAccent(savedAccent());

createApp(App).mount("#app");
