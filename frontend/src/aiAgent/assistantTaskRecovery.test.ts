import { afterEach, expect, it } from "vitest";
import { clearTokens, setTokens } from "../api/client";
import { lastAssistantTask, rememberAssistantTask, lastDashboardTask, rememberDashboardTask } from "./assistantTaskRecovery";

afterEach(() => { clearTokens(); localStorage.clear(); });
it("recovers only the current subject's task ID across sessions", () => {
  const token = (sub: string) => `header.${btoa(JSON.stringify({ sub }))}.signature`;
  setTokens(token("user-a"), "refresh-a");
  rememberAssistantTask("plan-a");
  clearTokens();
  expect(lastAssistantTask()).toBeNull();
  setTokens(token("user-b"), "refresh-b");
  expect(lastAssistantTask()).toBeNull();
  rememberAssistantTask("plan-b");
  setTokens(token("user-a"), "refresh-a");
  expect(lastAssistantTask()).toBe("plan-a");
  expect(localStorage.getItem("voltcanary.ai-task.user-a")).toBe("plan-a");
});
it("keeps working-task recovery scoped to the signed-in subject",()=>{
 const token=(sub:string)=>`header.${btoa(JSON.stringify({sub}))}.signature`;
 setTokens(token("user-a"),"refresh-a");rememberDashboardTask("task-a","branch-a");
 clearTokens();expect(lastDashboardTask()).toBeNull();
 setTokens(token("user-b"),"refresh-b");expect(lastDashboardTask()).toBeNull();
 setTokens(token("user-a"),"refresh-a");expect(lastDashboardTask()).toEqual({taskId:"task-a",branchId:"branch-a"});
});
it("remembers a selected review identity and clears it for a new turn",()=>{
 setTokens(`header.${btoa(JSON.stringify({sub:"user-a"}))}.signature`,"refresh");
 rememberDashboardTask("task","branch","old-review");
 expect(lastDashboardTask()).toEqual({taskId:"task",branchId:"branch",selectedReviewId:"old-review"});
 rememberDashboardTask("task","branch");
 expect(lastDashboardTask()).toEqual({taskId:"task",branchId:"branch"});
});
