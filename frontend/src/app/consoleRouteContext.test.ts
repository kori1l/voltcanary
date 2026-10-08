import { describe, expect, it } from "vitest";
import { consoleQuery, safeReturnTo } from "./consoleRouteContext";

describe("safe console context", () => {
  it.each(["//external.example/path", "https://external.example", "/login", "/register", "/forgot-password", "/reset-password?token=secret", "/%2f%2fexternal.example", "/devices\\evil", "/unknown", "javascript:alert(1)"])("rejects %s", (value) => expect(safeReturnTo(value)).toBe("/devices"));
  it("preserves canonical device interval and incident context while excluding credentials", () => {
    const route = "/devices/device-1?tab=telemetry&metric=power&from=2026-10-01T00%3A00%3A00Z&to=2026-10-02T00%3A00%3A00Z&alertId=alert-1&token=secret&metadata=private";
    const result = safeReturnTo(route);
    expect(result).toContain("tab=telemetry"); expect(result).toContain("alertId=alert-1");
    expect(result).toContain("from=2026-10-01"); expect(result).not.toContain("secret"); expect(result).not.toContain("metadata");
  });
  it("drops invalid enums, horizon, timezone and reversed intervals", () => {
    const params = consoleQuery("/settings/ml", "tab=unknown&family=unknown&horizonDays=-7&timezone=invalid&from=2026-10-02&to=2026-10-01");
    expect(params.toString()).toBe("");
  });
});
