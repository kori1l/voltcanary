import "@testing-library/jest-dom/vitest";
import { configure } from "@testing-library/react";

// These waits assert eventual UI state, not latency. Keep concurrent CI/Compose
// work from turning the one-second library default into a performance assertion.
configure({ asyncUtilTimeout: 3_000 });

// jsdom has no native dialog top layer; browser checks verify focus containment.
if (!HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute("open", ""); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute("open"); };
}
