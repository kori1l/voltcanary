import "@testing-library/jest-dom/vitest";

// jsdom has no native dialog top layer; browser checks verify focus containment.
if (!HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute("open", ""); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute("open"); };
}
