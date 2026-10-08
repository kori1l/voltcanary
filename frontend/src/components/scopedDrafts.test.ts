import { afterEach, expect, it } from "vitest";
import { clearTokens, setTokens } from "../api/client";
import { draftScope, readScopedDraft, saveScopedDraft, scopedDraftKey } from "./scopedDrafts";
const token = (sub: string) => `${btoa("{}")}\.${btoa(JSON.stringify({ sub }))}.fixture`;
afterEach(() => { clearTokens(); localStorage.clear(); });
it("isolates recovery by user, account, entity and base revision", () => {
  setTokens(token("user-A"), "fixture");
  const scope = draftScope("account-A", "rule-1", "revision-1")!;
  expect(saveScopedDraft(scope, { name: "Unsaved rule", password: "do-not-store", config: { token: "do-not-store" } }, ["name"])).toBe(true);
  expect(localStorage.getItem(scopedDraftKey(scope))).not.toContain("do-not-store");
  expect(readScopedDraft(scope, (value) => value.name)).toBe("Unsaved rule");
  expect(readScopedDraft({ ...scope, baseRevision: "revision-2" }, (value) => value.name)).toBeNull();
  expect(readScopedDraft({ ...scope, entityId: "rule-2" }, (value) => value.name)).toBeNull();
  expect(readScopedDraft({ ...scope, accountId: "account-B" }, (value) => value.name)).toBeNull();
  clearTokens(); expect(readScopedDraft(scope, (value) => value.name)).toBeNull();
  setTokens(token("user-B"), "fixture"); expect(readScopedDraft(scope, (value) => value.name)).toBeNull();
  setTokens(token("user-A"), "fixture"); expect(readScopedDraft(scope, (value) => value.name)).toBe("Unsaved rule");
});
it("uses a safe fallback for corrupt records and refuses credential fields", () => {
  setTokens(token("user-A"), "fixture"); const scope = draftScope("account-A", "rule", "1")!;
  localStorage.setItem(scopedDraftKey(scope), "{broken");
  expect(readScopedDraft(scope, (value) => value)).toBeNull();
  expect(saveScopedDraft(scope, { sourceToken: "secret" }, ["sourceToken"])).toBe(false);
  expect(saveScopedDraft(scope, { config: { credential: "secret" } }, ["config"])).toBe(false);
});
