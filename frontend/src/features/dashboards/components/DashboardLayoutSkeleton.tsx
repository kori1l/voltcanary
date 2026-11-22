import { useEffect, useState } from "react";
import type { LayoutHint } from "../model/dashboardLayoutCache";

export function DashboardLayoutSkeleton({ hint }: { hint?: LayoutHint }) {
  const [width, setWidth] = useState(window.innerWidth);
  useEffect(() => {
    const resize = () => setWidth(window.innerWidth);
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, []);
  const key = width >= 1200 ? "desktop" : width >= 768 ? "tablet" : "mobile";
  const grid = hint?.[key].grid ?? { columns: key === "mobile" ? 1 : 6, rowHeight: 48, gap: 12 };
  const positions =
    hint?.[key].positions ??
    Array.from({ length: 6 }, (_, i) => ({
      widgetId: String(i),
      x: key === "mobile" ? 0 : (i % 2) * 3,
      y: Math.floor(i / (key === "mobile" ? 1 : 2)) * 3,
      w: key === "mobile" ? 1 : 3,
      h: 3,
    }));
  return (
    <div
      className="dashboard-grid"
      aria-hidden="true"
      style={{
        gridTemplateColumns: `repeat(${grid.columns},minmax(0,1fr))`,
        gridAutoRows: grid.rowHeight,
        gap: grid.gap,
      }}
    >
      {positions.map((p) => (
        <div
          key={p.widgetId}
          className="panel dashboard-layout-skeleton"
          style={{ gridColumn: `${p.x + 1} / span ${p.w}`, gridRow: `${p.y + 1} / span ${p.h}` }}
        >
          <div />
          <div />
          <div />
        </div>
      ))}
    </div>
  );
}
