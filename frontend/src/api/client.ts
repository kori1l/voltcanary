import { withSessionRefreshLock } from "./sessionRefreshLock";
import { RequestDeadlineError, withRequestDeadline } from "./requestDeadline";

export interface ApiRequestOptions extends RequestInit {
  timeoutMs?: number;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";
const ACCESS_TOKEN_KEY = "voltcanary.accessToken";
const BROWSER_SESSION_KEY = "voltcanary.browserSessionId";
const SESSION_REVISION_KEY = "voltcanary.sessionRevision";
// Discard the former script-readable refresh credential during upgrade.
localStorage.removeItem("voltcanary.refreshToken");
const SESSION_IDENTITY_KEY = "voltcanary.sessionIdentity";
let observedIdentity = localStorage.getItem(SESSION_IDENTITY_KEY);
export const AUTH_CHANGED = "voltcanary.auth-changed";
export const SESSION_EXPIRED = "voltcanary.session-expired";
let authEpoch = 0;
let identityEpoch = 0;
export function sessionIdentityRevision() {
  const current = localStorage.getItem(SESSION_IDENTITY_KEY);
  if (current !== observedIdentity) {
    observedIdentity = current;
    identityEpoch++;
  }
  return identityEpoch;
}
let expired = false;
let refreshFlight: { sessionId: string; epoch: number; promise: Promise<boolean> } | null = null;
export function sessionStatus() {
  return getAccessToken() ? "authenticated" : expired ? "expired" : "anonymous";
}
export function subscribeSession(listener: () => void) {
  window.addEventListener(AUTH_CHANGED, listener);
  return () => window.removeEventListener(AUTH_CHANGED, listener);
}
window.addEventListener("storage", (event) => {
  if (
    event.key === null ||
    event.key === ACCESS_TOKEN_KEY ||
    event.key === BROWSER_SESSION_KEY ||
    event.key === SESSION_REVISION_KEY ||
    event.key === SESSION_IDENTITY_KEY
  ) {
    authEpoch++;
    if (event.key === null) identityEpoch++;
    sessionIdentityRevision();
    expired = false;
    window.dispatchEvent(new Event(AUTH_CHANGED));
  }
});

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string,
    public readonly fieldErrors?: Record<string, string>,
  ) {
    super(sanitizeApiMessage(message));
    this.name = "ApiError";
  }
}
export function sanitizeApiMessage(message: string) {
  if (
    /^(?:reset |refresh |verification )?token (?:is |has )?(?:expired|invalid|revoked)\.?$/i.test(
      message.trim(),
    )
  )
    return "Recovery or session code expired or invalid. Request a new code or sign in again.";
  if (
    /bearer\s|password|secret|api.?key|token[\s:=]|credential|authorization|telegram\.org|webhook/i.test(
      message,
    )
  )
    return "The request could not be completed. Review the configuration and permissions.";
  return message.replace(/https?:\/\/[^\s)]+/gi, "[service address omitted]").slice(0, 500);
}

export function getAccessToken() {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getSessionId() {
  return localStorage.getItem(BROWSER_SESSION_KEY);
}

export function setSession(accessToken: string, sessionId: string) {
  localStorage.setItem(BROWSER_SESSION_KEY, sessionId);
  localStorage.setItem(SESSION_IDENTITY_KEY, crypto.randomUUID());
  sessionIdentityRevision();
  writeAccessToken(accessToken);
}
function writeAccessToken(accessToken: string) {
  authEpoch++;
  expired = false;
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  localStorage.setItem(SESSION_REVISION_KEY, crypto.randomUUID());
  window.dispatchEvent(new Event(AUTH_CHANGED));
}

export function clearTokens() {
  expired = false;
  removeTokens();
}
function removeTokens() {
  localStorage.removeItem(SESSION_IDENTITY_KEY);
  observedIdentity = null;
  identityEpoch++;
  authEpoch++;
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(BROWSER_SESSION_KEY);
  localStorage.removeItem(SESSION_REVISION_KEY);
  window.dispatchEvent(new Event(AUTH_CHANGED));
}
function expireSession() {
  if (expired) return;
  expired = true;
  removeTokens();
  window.dispatchEvent(new Event(SESSION_EXPIRED));
}

export function isAuthenticated() {
  return Boolean(getAccessToken());
}

/** Renew before the bounded AI turn, rather than after a token expires inside a downstream read. */
export async function prepareAiSession() {
  const token = getAccessToken();
  if (!token) return;
  let expiry: number;
  try {
    const payload = token.split(".")[1];
    const normalized = payload.replace(/-/g, "+").replace(/_/g, "/");
    expiry = JSON.parse(atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "="))).exp;
  } catch {
    return;
  }
  if (typeof expiry !== "number" || expiry * 1000 > Date.now() + 300_000) return;
  const identity = sessionIdentityRevision();
  if (!getSessionId() || !(await refreshAccessToken()))
    throw new ApiError("Renew your session before preparing this request.", 401);
  if (identity !== sessionIdentityRevision())
    throw new ApiError("Authentication scope changed. Retry in the current session.", 401);
}

export async function apiRequest<TResponse>(
  path: string,
  options: ApiRequestOptions = {},
): Promise<TResponse> {
  return requestWithAuth<TResponse>(path, options, true, "json");
}

export async function apiTextRequest(
  path: string,
  options: ApiRequestOptions = {},
): Promise<string> {
  return requestWithAuth<string>(path, options, true, "text");
}
export async function apiResponseRequest(
  path: string,
  options: ApiRequestOptions = {},
): Promise<Response> {
  return requestWithAuth<Response>(path, options, true, "response");
}

async function requestWithAuth<TResponse>(
  path: string,
  options: ApiRequestOptions,
  allowRefresh: boolean,
  responseType: "json" | "text" | "response",
): Promise<TResponse> {
  // Assistant calls have their own server-owned turn budget; ordinary console reads are shorter.
  const { timeoutMs = path.startsWith("/ai-agent/") ? 180_000 : 30_000, ...request } = options;
  try {
    return await withRequestDeadline(request.signal, timeoutMs, (signal) =>
      performRequestWithAuth<TResponse>(path, { ...request, signal }, allowRefresh, responseType),
    );
  } catch (failure) {
    if (failure instanceof RequestDeadlineError)
      throw new ApiError(failure.message, 504, "REQUEST_TIMEOUT");
    throw failure;
  }
}

async function performRequestWithAuth<TResponse>(
  path: string,
  options: RequestInit,
  allowRefresh: boolean,
  responseType: "json" | "text" | "response",
): Promise<TResponse> {
  const token = getAccessToken();
  const requestIdentity = sessionIdentityRevision();
  const headers = new Headers(options.headers);
  if (
    options.body !== undefined &&
    !(options.body instanceof FormData) &&
    !headers.has("Content-Type")
  ) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, { ...options, headers });
  } catch {
    if (options.signal?.aborted) throw new DOMException("Obsolete read", "AbortError");
    throw new ApiError(
      "The server response was not received. Check your connection and refresh current records.",
      0,
    );
  }
  if (options.signal?.aborted) throw new DOMException("Obsolete read", "AbortError");
  if (requestIdentity !== sessionIdentityRevision())
    throw new ApiError("Authentication scope changed. Retry in the current session.", 401);

  if (
    response.status === 401 &&
    allowRefresh &&
    !path.startsWith("/auth/browser/") &&
    !path.startsWith("/auth/login") &&
    !path.startsWith("/auth/refresh")
  ) {
    if (requestIdentity !== sessionIdentityRevision())
      throw new ApiError("Authentication scope changed. Retry in the current session.", 401);
    if (token && getAccessToken() && token !== getAccessToken())
      return requestWithAuth<TResponse>(path, options, false, responseType);
    const refreshed = await refreshAccessToken();
    if (requestIdentity !== sessionIdentityRevision())
      throw new ApiError("Authentication scope changed. Retry in the current session.", 401);
    if (refreshed) {
      return requestWithAuth<TResponse>(path, options, false, responseType);
    }
  }

  if (!response.ok) {
    const detail = (await response
      .clone()
      .json()
      .catch(() => null)) as { code?: unknown; fieldErrors?: unknown } | null;
    const code =
      typeof detail?.code === "string" && /^[A-Za-z0-9_]{1,80}$/.test(detail.code)
        ? detail.code
        : undefined;
    const fields =
      detail?.fieldErrors &&
      typeof detail.fieldErrors === "object" &&
      !Array.isArray(detail.fieldErrors)
        ? Object.fromEntries(
            Object.entries(detail.fieldErrors)
              .filter(
                ([key, value]) =>
                  /^[a-zA-Z][a-zA-Z0-9.]{0,80}$/.test(key) &&
                  !/password|secret|token|credential/i.test(key) &&
                  typeof value === "string",
              )
              .map(([key, value]) => [key, sanitizeApiMessage(String(value))]),
          )
        : undefined;
    const message = await errorMessage(response);
    if (requestIdentity !== sessionIdentityRevision())
      throw new ApiError("Authentication scope changed. Retry in the current session.", 401);
    throw new ApiError(message, response.status, code, fields);
  }

  if (responseType === "response") return response as TResponse;
  if (response.status === 204) return (responseType === "text" ? "" : undefined) as TResponse;
  if (responseType === "text") {
    const body = await response.text();
    if (requestIdentity !== sessionIdentityRevision())
      throw new ApiError("Authentication scope changed. Retry in the current session.", 401);
    return body as TResponse;
  }
  const body = (await response.json()) as TResponse;
  if (requestIdentity !== sessionIdentityRevision())
    throw new ApiError("Authentication scope changed. Retry in the current session.", 401);
  return body;
}

async function refreshAccessToken() {
  const sessionId = getSessionId();
  const revision = localStorage.getItem(SESSION_REVISION_KEY);
  if (!sessionId) {
    if (getAccessToken()) expireSession();
    return false;
  }
  if (refreshFlight?.sessionId === sessionId && refreshFlight.epoch === authEpoch)
    return refreshFlight.promise;
  const epoch = authEpoch;
  const refreshIdentity = sessionIdentityRevision();
  const promise = withSessionRefreshLock(async () => {
    // A preceding tab may already have rotated the HttpOnly cookie while we waited.
    if (
      localStorage.getItem(SESSION_REVISION_KEY) !== revision ||
      refreshIdentity !== sessionIdentityRevision()
    )
      return Boolean(getAccessToken());
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15_000);
    try {
      let response: Response;
      try {
        response = await fetch(
          `${API_BASE_URL}/auth/browser/${encodeURIComponent(sessionId)}/refresh`,
          {
            credentials: "include",
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: "{}",
            signal: controller.signal,
          },
        );
      } catch {
        throw new ApiError("Session refresh unavailable. Retry when the connection recovers.", 503);
      }

      if (epoch !== authEpoch || refreshIdentity !== sessionIdentityRevision())
        return Boolean(getAccessToken());

      if (!response.ok) {
        if ([401, 403].includes(response.status)) {
          expireSession();
          return false;
        }
        throw new ApiError(
          "Session refresh unavailable. Retry when the service recovers.",
          response.status,
        );
      }

      const tokens = (await response.json()) as {
        accessToken: string;
        sessionId: string;
      } | null;
      if (!tokens || typeof tokens !== "object")
        throw new ApiError("Session refresh returned an invalid response.", 503);
      if (
        typeof tokens.accessToken !== "string" ||
        !tokens.accessToken.trim() ||
        tokens.sessionId !== sessionId
      )
        throw new ApiError("Session refresh returned an invalid response.", 503);
      if (epoch !== authEpoch || refreshIdentity !== sessionIdentityRevision())
        return Boolean(getAccessToken());
      writeAccessToken(tokens.accessToken);
      return true;
    } finally {
      clearTimeout(timeout);
    }
  }).catch((failure: unknown) => {
    if (failure instanceof ApiError) throw failure;
    throw new ApiError(
      "Session refresh coordination unavailable. Retry when the connection recovers.",
      503,
    );
  });
  const flight = { sessionId, epoch, promise };
  refreshFlight = flight;
  try {
    return await promise;
  } finally {
    if (refreshFlight === flight) refreshFlight = null;
  }
}

async function errorMessage(response: Response) {
  const body = await response.text();
  if (!body) {
    return `API request failed: ${response.status} ${response.statusText}`;
  }

  try {
    const value: unknown = JSON.parse(body);
    if (!value || typeof value !== "object" || Array.isArray(value))
      throw new Error("Invalid error response");
    const raw = value as Record<string, unknown>;
    const parsed = {
      message: typeof raw.message === "string" ? raw.message : undefined,
      code: typeof raw.code === "string" ? raw.code : undefined,
      error: typeof raw.error === "string" ? raw.error : undefined,
      status: typeof raw.status === "number" ? raw.status : undefined,
      path: typeof raw.path === "string" ? raw.path : undefined,
      dependencies:
        raw.dependencies && typeof raw.dependencies === "object" && !Array.isArray(raw.dependencies)
          ? Object.fromEntries(
              Object.entries(raw.dependencies)
                .filter(
                  (entry): entry is [string, number] =>
                    typeof entry[1] === "number" && Number.isFinite(entry[1]),
                )
                .slice(0, 20),
            )
          : undefined,
    };
    if (parsed.message && parsed.code) {
      const dependencies = formatDependencies(parsed.dependencies);
      return dependencies
        ? `${parsed.message} (${parsed.code}): ${dependencies}`
        : `${parsed.message} (${parsed.code})`;
    }
    if (parsed.message) {
      return parsed.message;
    }
    if (parsed.code) {
      return parsed.code;
    }
    if (parsed.error) {
      return parsed.path ? `${parsed.error} for ${parsed.path}` : parsed.error;
    }
    if (parsed.status) {
      return parsed.path
        ? `API request failed: ${parsed.status} for ${parsed.path}`
        : `API request failed: ${parsed.status}`;
    }
    return `API request failed: ${response.status}. Review the current resource and configuration.`;
  } catch {
    return `API request failed: ${response.status}. Review the current resource and configuration.`;
  }
}

function formatDependencies(dependencies: Record<string, number> | undefined) {
  if (!dependencies || Object.keys(dependencies).length === 0) {
    return null;
  }
  return Object.entries(dependencies)
    .map(([key, value]) => `${key}=${value}`)
    .join(", ");
}
