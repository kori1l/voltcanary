import { BusFront, CarFront, TramFront } from "lucide-react";
import { createRoot } from "react-dom/client";

export type TransportKind = "tram" | "bus" | "car";
export function transportKind(sourceId: string): TransportKind {
  return sourceId.startsWith("hsl-tram-")
    ? "tram"
    : sourceId.startsWith("hsl-bus-")
      ? "bus"
      : "car";
}

/** The transport glyph stays upright; a small outer pointer conveys heading. */
export function createTransportMarker(kind: TransportKind) {
  const element = document.createElement("button");
  element.type = "button";
  element.className = "fleet-marker";
  const glyph = document.createElement("span");
  glyph.className = "fleet-marker-glyph";
  const direction = document.createElement("span");
  direction.className = "fleet-marker-direction";
  direction.setAttribute("aria-hidden", "true");
  element.append(glyph, direction);
  const root = createRoot(glyph);
  const Icon = kind === "tram" ? TramFront : kind === "bus" ? BusFront : CarFront;
  root.render(<Icon size={18} strokeWidth={2} aria-hidden="true" />);
  return {
    element,
    setHeading: (heading: number | null) => {
      direction.hidden = heading == null;
      direction.style.transform = `rotate(${heading ?? 0}deg)`;
    },
    dispose: () => root.unmount(),
  };
}
