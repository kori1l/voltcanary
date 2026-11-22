export type AssistantWindowMode = "floating" | "sidebar" | "expanded";
export type AssistantWindowPosition = "left" | "center" | "right";
export interface AssistantWindowPreferences {
  mode: AssistantWindowMode;
  position: AssistantWindowPosition;
}
const storageKey = "voltcanary.assistant.window.v1";
export function readAssistantWindow(): AssistantWindowPreferences {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey) ?? "null");
    return {
      mode: ["floating", "sidebar", "expanded"].includes(value?.mode) ? value.mode : "floating",
      position: ["left", "center", "right"].includes(value?.position) ? value.position : "right",
    };
  } catch {
    return { mode: "floating", position: "right" };
  }
}
export function saveAssistantWindow(value: AssistantWindowPreferences) {
  try {
    localStorage.setItem(storageKey, JSON.stringify(value));
  } catch {
    /* Presentation still works when storage is disabled. */
  }
}
export function openAssistant() {
  window.dispatchEvent(new Event("voltcanary:open-assistant"));
}
