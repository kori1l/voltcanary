export interface DraftScope { userId: string; accountId: string; entityId: string; baseRevision: string }
const PREFIX = "voltcanary.draft.v1.";
const forbidden = /password|secret|token|credential|authorization|api.?key|__proto__|constructor|prototype/i;
export function sessionUserId(): string | null {
  try {
    const token = localStorage.getItem("voltcanary.accessToken");
    const part = token?.split(".")[1];
    if (!part) return null;
    const claims = JSON.parse(atob(part.replace(/-/g, "+").replace(/_/g, "/"))) as { sub?: unknown };
    return typeof claims.sub === "string" && /^[a-zA-Z0-9_-]{1,128}$/.test(claims.sub) ? claims.sub : null;
  } catch { return null; }
}
export function draftScope(accountId: string | null | undefined, entityId: string, baseRevision: string): DraftScope | null {
  const userId = sessionUserId();
  return userId && accountId && accountId !== "unknown-account" ? { userId, accountId, entityId, baseRevision } : null;
}
export function scopedDraftKey(scope: DraftScope): string {
  return PREFIX + [scope.userId, scope.accountId, scope.entityId, scope.baseRevision].map(encodeURIComponent).join(".");
}
function primitive(value: unknown): boolean {
  return value === null || typeof value === "boolean" || typeof value === "number" && Number.isFinite(value) || typeof value === "string" && value.length <= 4096 || Array.isArray(value) && value.length <= 100 && value.every((item) => !Array.isArray(item) && primitive(item));
}
export function saveScopedDraft(scope: DraftScope | null, value: object, allowedFields: readonly string[]): boolean {
  if (!scope || sessionUserId() !== scope.userId || allowedFields.some((key) => forbidden.test(key))) return false;
  const data: Record<string, unknown> = {};
  for (const key of allowedFields) {
    const field = (value as Record<string, unknown>)[key];
    if (field === undefined) continue;
    if (!primitive(field)) return false;
    data[key] = field;
  }
  try { localStorage.setItem(scopedDraftKey(scope), JSON.stringify({ version: 1, scope, data })); return true; } catch { return false; }
}
export function readScopedDraft<T>(scope: DraftScope | null, decode: (value: Record<string, unknown>) => T | null): T | null {
  if (!scope || sessionUserId() !== scope.userId) return null;
  try {
    const raw = localStorage.getItem(scopedDraftKey(scope));
    if (!raw || raw.length > 64_000) return null;
    const value = JSON.parse(raw) as { version?: number; scope?: DraftScope; data?: Record<string, unknown> };
    if (value.version !== 1 || !(["userId", "accountId", "entityId", "baseRevision"] as const).every((key) => value.scope?.[key] === scope[key]) || !value.data || Array.isArray(value.data) || typeof value.data !== "object" || Object.keys(value.data).some((key) => forbidden.test(key)) || !Object.values(value.data).every(primitive)) return null;
    return decode(value.data);
  } catch { return null; }
}
export function clearScopedDraft(scope: DraftScope | null) {
  if (!scope || sessionUserId() !== scope.userId) return;
  try { localStorage.removeItem(scopedDraftKey(scope)); } catch { /* Recovery storage is best effort. */ }
}

export function decodeDraftFields<T extends object>(value: Record<string, unknown>, defaults: T): Partial<T> | null {
  const decoded: Record<string, unknown> = {};
  for (const key of Object.keys(defaults)) {
    if (!(key in value)) continue;
    const field = value[key], expected = (defaults as Record<string, unknown>)[key];
    if (expected === null ? field !== null && typeof field !== "string" : Array.isArray(expected) ? !Array.isArray(field) || !field.every((item) => typeof item === "string") : typeof field !== typeof expected || !primitive(field)) return null;
    decoded[key] = field;
  }
  return decoded as Partial<T>;
}
export function readPrivateLegacyDraft<T>(scope: DraftScope | null, legacyKey: string, decode: (value: Record<string, unknown>) => T | null, allowedFields: readonly string[]): T | null {
  if (!scope || scope.userId !== scope.accountId || sessionUserId() !== scope.userId) return null;
  try {
    const raw = localStorage.getItem(legacyKey);
    if (!raw || raw.length > 64_000) return null;
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).some((key) => forbidden.test(key))) return null;
    const decoded = decode(value as Record<string, unknown>);
    if (decoded && typeof decoded === "object" && saveScopedDraft(scope, decoded, allowedFields)) localStorage.removeItem(legacyKey);
    return decoded;
  } catch { return null; }
}
