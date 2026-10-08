const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";
const ACCESS_TOKEN_KEY = "voltcanary.accessToken";
const REFRESH_TOKEN_KEY = "voltcanary.refreshToken";
export const AUTH_CHANGED = "voltcanary.auth-changed";
export const SESSION_EXPIRED = "voltcanary.session-expired";
let authEpoch = 0;
let identityEpoch = 0;
export function sessionIdentityRevision() { return identityEpoch; }
let expired = false;
let refreshFlight: { token: string; epoch: number; promise: Promise<boolean> } | null = null;
export function sessionStatus() { return getAccessToken() ? "authenticated" : expired ? "expired" : "anonymous"; }
export function subscribeSession(listener: () => void) {
  window.addEventListener(AUTH_CHANGED, listener);
  return () => window.removeEventListener(AUTH_CHANGED, listener);
}
window.addEventListener("storage", (event) => {
  if (event.key === null || event.key === ACCESS_TOKEN_KEY || event.key === REFRESH_TOKEN_KEY) {
    authEpoch++; identityEpoch++; expired = false;
    window.dispatchEvent(new Event(AUTH_CHANGED));
  }
});

export class ApiError extends Error {
  constructor(message: string, public readonly status: number, public readonly code?: string, public readonly fieldErrors?: Record<string, string>) { super(sanitizeApiMessage(message)); this.name = "ApiError"; }
}
export function sanitizeApiMessage(message: string) {
  if (/^(?:reset |refresh |verification )?token (?:is |has )?(?:expired|invalid|revoked)\.?$/i.test(message.trim())) return "Recovery or session code expired or invalid. Request a new code or sign in again.";
  if (/bearer\s|password|secret|api.?key|token[\s:=]|credential|authorization|telegram\.org|webhook/i.test(message)) return "The request could not be completed. Review the configuration and permissions.";
  return message.replace(/https?:\/\/[^\s)]+/gi, "[service address omitted]").slice(0, 500);
}

export function getAccessToken() {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken() {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setTokens(accessToken: string, refreshToken: string) {
  identityEpoch++;
  writeTokens(accessToken, refreshToken);
}
function writeTokens(accessToken: string, refreshToken: string) {
  authEpoch++; expired = false;
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
  window.dispatchEvent(new Event(AUTH_CHANGED));
}

export function clearTokens() {
  expired = false;
  removeTokens();
}
function removeTokens() {
  identityEpoch++;
  authEpoch++;
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
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

export async function apiRequest<TResponse>(
  path: string,
  options: RequestInit = {},
): Promise<TResponse> {
  return requestWithAuth<TResponse>(path, options, true, "json");
}

export async function apiTextRequest(
  path: string,
  options: RequestInit = {},
): Promise<string> {
  return requestWithAuth<string>(path, options, true, "text");
}

async function requestWithAuth<TResponse>(
  path: string,
  options: RequestInit,
  allowRefresh: boolean,
  responseType: "json" | "text",
): Promise<TResponse> {
  const token = getAccessToken();
  const requestIdentity = identityEpoch;
  const headers = new Headers(options.headers);
  if (options.body !== undefined && !(options.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  let response: Response;
  try { response = await fetch(`${API_BASE_URL}${path}`, { ...options, headers }); }
  catch { if (options.signal?.aborted) throw new DOMException("Obsolete read", "AbortError"); throw new ApiError("The server response was not received. Check your connection and refresh current records.", 0); }
  if (options.signal?.aborted) throw new DOMException("Obsolete read", "AbortError");
  if (requestIdentity !== identityEpoch) throw new ApiError("Authentication scope changed. Retry in the current session.", 401);

  if (response.status === 401 && allowRefresh && !path.startsWith("/auth/login") && !path.startsWith("/auth/refresh")) {
    if (requestIdentity !== identityEpoch) throw new ApiError("Authentication scope changed. Retry in the current session.", 401);
    if (token && getAccessToken() && token !== getAccessToken()) return requestWithAuth<TResponse>(path, options, false, responseType);
    const refreshed = await refreshAccessToken();
    if (requestIdentity !== identityEpoch) throw new ApiError("Authentication scope changed. Retry in the current session.", 401);
    if (refreshed) {
      return requestWithAuth<TResponse>(path, options, false, responseType);
    }
  }

  if (!response.ok) {
    const detail = await response.clone().json().catch(() => null) as { code?: unknown; fieldErrors?: unknown } | null;
    const code = typeof detail?.code === "string" && /^[A-Z0-9_]{1,80}$/.test(detail.code) ? detail.code : undefined;
    const fields = detail?.fieldErrors && typeof detail.fieldErrors === "object" && !Array.isArray(detail.fieldErrors) ? Object.fromEntries(Object.entries(detail.fieldErrors).filter(([key, value]) => /^[a-zA-Z][a-zA-Z0-9.]{0,80}$/.test(key) && !/password|secret|token|credential/i.test(key) && typeof value === "string").map(([key, value]) => [key, sanitizeApiMessage(String(value))])) : undefined;
    throw new ApiError(await errorMessage(response), response.status, code, fields);
  }

  if (response.status === 204) {
    return undefined as TResponse;
  }
  if (responseType === "text") {
    const body = await response.text();
    if (requestIdentity !== identityEpoch) throw new ApiError("Authentication scope changed. Retry in the current session.", 401);
    return body as TResponse;
  }
  const body = await response.json() as TResponse;
  if (requestIdentity !== identityEpoch) throw new ApiError("Authentication scope changed. Retry in the current session.", 401);
  return body;
}

async function refreshAccessToken() {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    if (getAccessToken()) expireSession();
    return false;
  }
  if (refreshFlight?.token === refreshToken && refreshFlight.epoch === authEpoch) return refreshFlight.promise;
  const epoch = authEpoch;
  const promise = (async () => {
  let response: Response;
  try { response = await fetch(`${API_BASE_URL}/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
  }); } catch { throw new ApiError("Session refresh unavailable. Retry when the connection recovers.", 503); }

  if (epoch !== authEpoch) return Boolean(getAccessToken());

  if (!response.ok) {
    if ([401, 403].includes(response.status)) { expireSession(); return false; }
    throw new ApiError("Session refresh unavailable. Retry when the service recovers.", response.status);
  }

  const tokens = (await response.json()) as { accessToken: string; refreshToken: string };
  if (!tokens.accessToken || !tokens.refreshToken) throw new ApiError("Session refresh returned an invalid response.", 503);
  if (epoch !== authEpoch) return Boolean(getAccessToken());
  writeTokens(tokens.accessToken, tokens.refreshToken);
  return true;
  })();
  const flight = { token: refreshToken, epoch, promise };
  refreshFlight = flight;
  try { return await promise; } finally { if (refreshFlight === flight) refreshFlight = null; }
}

async function errorMessage(response: Response) {
  const body = await response.text();
  if (!body) {
    return `API request failed: ${response.status} ${response.statusText}`;
  }

  try {
    const parsed = JSON.parse(body) as {
      message?: string;
      code?: string;
      error?: string;
      status?: number;
      path?: string;
      dependencies?: Record<string, number>;
    };
    if (parsed.message && parsed.code) {
      const dependencies = formatDependencies(parsed.dependencies);
      return dependencies ? `${parsed.message} (${parsed.code}): ${dependencies}` : `${parsed.message} (${parsed.code})`;
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
