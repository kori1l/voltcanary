const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";
const ACCESS_TOKEN_KEY = "voltcanary.accessToken";
const REFRESH_TOKEN_KEY = "voltcanary.refreshToken";

export function getAccessToken() {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken() {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setTokens(accessToken: string, refreshToken: string) {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
}

export function clearTokens() {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
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
  const headers = new Headers(options.headers);
  if (options.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
  });

  if (response.status === 401 && allowRefresh && !path.startsWith("/auth/login") && !path.startsWith("/auth/refresh")) {
    const refreshed = await refreshAccessToken();
    if (refreshed) {
      return requestWithAuth<TResponse>(path, options, false, responseType);
    }
  }

  if (!response.ok) {
    throw new Error(await errorMessage(response));
  }

  if (response.status === 204) {
    return undefined as TResponse;
  }
  if (responseType === "text") {
    return response.text() as Promise<TResponse>;
  }
  return response.json() as Promise<TResponse>;
}

async function refreshAccessToken() {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    return false;
  }

  const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
  });

  if (!response.ok) {
    clearTokens();
    return false;
  }

  const tokens = (await response.json()) as { accessToken: string; refreshToken: string };
  setTokens(tokens.accessToken, tokens.refreshToken);
  return true;
}

async function errorMessage(response: Response) {
  const body = await response.text();
  if (!body) {
    return `API request failed: ${response.status} ${response.statusText}`;
  }

  try {
    const parsed = JSON.parse(body) as { message?: string; code?: string };
    if (parsed.message && parsed.code) {
      return `${parsed.message} (${parsed.code})`;
    }
    return parsed.message ?? parsed.code ?? body;
  } catch {
    return body;
  }
}
