import type { DashboardDefinition, DashboardMetadata } from "../../../dashboards/dashboardsApi";
import { sessionUserId, draftScope, scopedDraftKey } from "../../../components/scopedDrafts";
const forbidden = /password|secret|credential|token|authorization|headers|recipients|sql|code|html|iframe|rawvalues|readings|__proto__|constructor|prototype/i;
export function safeDefinition(value: unknown, depth = 0): boolean {
  if(depth>12) return false;
  if(value===null || typeof value==="boolean") return true;
  if(typeof value==="number") return Number.isFinite(value);
  if(typeof value==="string") return value.length<=4000 && !/(?:https?:\/\/|<\s*\/?\s*[a-z]|javascript\s*:|Bearer\s|PRIVATE KEY)/i.test(value);
  if(Array.isArray(value)) return value.length<=500 && value.every((item)=>safeDefinition(item,depth+1));
  return typeof value==="object" && Object.entries(value).length<=64 && Object.entries(value).every(([key,item])=>!forbidden.test(key) && safeDefinition(item,depth+1));
}
function key(metadata:DashboardMetadata,baseRevision:number) {
  if(sessionUserId()!==metadata.ownerUserId) return null;
  const scope=draftScope(metadata.accountId,`dashboard-${metadata.id}`,String(baseRevision));
  return scope ? scopedDraftKey(scope) : null;
}
export function writeRecovery(metadata:DashboardMetadata,baseRevision:number,definition:DashboardDefinition) {
  const target=key(metadata,baseRevision);
  if(!target || !safeDefinition(definition)) return;
  const data=JSON.stringify(definition); if(data.length>131072) return;
  try {localStorage.setItem(target,data);} catch { /* Server draft remains canonical. */ }
}
export function clearRecovery(metadata:DashboardMetadata,baseRevision:number) {const target=key(metadata,baseRevision);if(target) try {localStorage.removeItem(target);} catch { /* Best effort. */ }}
export function readRecovery(metadata:DashboardMetadata,baseRevision:number):DashboardDefinition|null {
  const target=key(metadata,baseRevision);if(!target) return null;
  try {const raw=localStorage.getItem(target);if(!raw || raw.length>131072) return null;const data=JSON.parse(raw);return safeDefinition(data) && data.schemaVersion===1 && typeof data.name==="string" && Array.isArray(data.widgets) && Array.isArray(data.sections) && data.layouts ? data : null;} catch {return null;}
}
