import {expect,it} from "vitest";
import {clearTokens} from "../../../api/client";
import {blankDashboard} from "./dashboardLayout";
import {dashboardLayoutHint,rememberDashboardLayout} from "./dashboardLayoutCache";
it("retains bounded layout hints without evidence and clears them on session changes",()=>{
  const definition=blankDashboard("Private dashboard");
  definition.defaultContext={scope:{deviceIds:["private-device"]}};
  rememberDashboardLayout("dashboard",definition);
  const hint=dashboardLayoutHint("dashboard");expect(hint).toBeDefined();
  expect(JSON.stringify(hint)).not.toContain("private-device");expect(JSON.stringify(hint)).not.toContain("Private dashboard");
  for(let i=0;i<8;i++)rememberDashboardLayout(String(i),definition);
  expect(dashboardLayoutHint("dashboard")).toBeUndefined();
  clearTokens();expect(dashboardLayoutHint("7")).toBeUndefined();
});
