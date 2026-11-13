import { getAccessToken } from "../api/client";

// The subject namespaces a non-secret task ID; server authorization remains authoritative.
function key() {
  try {
    const payload = getAccessToken()?.split(".")[1];
    if (!payload) return null;
    const subject: unknown = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/"))).sub;
    return typeof subject === "string" && /^[a-zA-Z0-9-]{1,80}$/.test(subject) ? `voltcanary.ai-task.${subject}` : null;
  } catch { return null; }
}

export function rememberAssistantTask(id: string) {
  const storageKey = key();
  try {if (storageKey) localStorage.setItem(storageKey, id);} catch { /* The active task remains usable without browser storage. */ }
}

export function lastAssistantTask() {
  const storageKey = key();
  try {return storageKey ? localStorage.getItem(storageKey) : null;} catch {return null;}
}

export function rememberDashboardTask(taskId:string, branchId:string, selectedReviewId?:string) {
  const storageKey=key();
  try {if(storageKey)localStorage.setItem(`${storageKey}.dashboard`,JSON.stringify({taskId,branchId,...(selectedReviewId?{selectedReviewId}:{})}));}catch { /* Server state remains authoritative. */ }
}
export function lastDashboardTask():{taskId:string;branchId:string;selectedReviewId?:string}|null {
  const storageKey=key();
  try {
    const value=storageKey?JSON.parse(localStorage.getItem(`${storageKey}.dashboard`)??"null"):null;
    return value&&typeof value.taskId==="string"&&typeof value.branchId==="string"?{taskId:value.taskId,branchId:value.branchId,...(typeof value.selectedReviewId==="string"?{selectedReviewId:value.selectedReviewId}:{})}:null;
  }catch {return null;}
}
export function forgetDashboardTask() {
  const storageKey=key();
  try {if(storageKey)localStorage.removeItem(`${storageKey}.dashboard`);}catch { /* No usable local recovery pointer. */ }
}
