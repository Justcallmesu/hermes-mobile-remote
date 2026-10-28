// Accent colours, the same set as the Android app (ui/Theme.kt): [dark-mode colour, light-mode colour].
export const ACCENTS = {
  gold: ["Gold", "#e8b54a", "#8a5f00"],
  orange: ["Orange", "#ff9f4a", "#b45309"],
  coral: ["Coral", "#ff8a75", "#c2412d"],
  rose: ["Rose", "#f28fa8", "#b0305a"],
  violet: ["Violet", "#b794f6", "#6a3fc4"],
  indigo: ["Indigo", "#8c9eff", "#3730a3"],
  ocean: ["Ocean", "#6fa8f5", "#1f5fb8"],
  sky: ["Sky", "#7dd3fc", "#0369a1"],
  teal: ["Teal", "#4fd1b5", "#0f766e"],
  forest: ["Forest", "#6ccb8a", "#1e7a43"],
  lime: ["Lime", "#b5e05a", "#4d7c0f"],
  slate: ["Slate", "#a3b1c6", "#475569"],
};
const KEY = "accent";

export function savedAccent() {
  const a = globalThis.localStorage?.getItem(KEY);
  return a in ACCENTS ? a : "gold";
}

/** Set the accent CSS variables (both modes) on the page and remember the choice. */
export function applyAccent(id) {
  const [, dark, light] = ACCENTS[id] ?? ACCENTS.gold;
  const root = document.documentElement.style;
  root.setProperty("--accent-dark", dark);
  root.setProperty("--accent-light", light);
  globalThis.localStorage?.setItem(KEY, id in ACCENTS ? id : "gold");
}
