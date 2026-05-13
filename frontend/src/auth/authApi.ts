import { apiRequest } from "../api/client";

export interface LoginRequest {
  email: string;
  password: string;
}

export function login(request: LoginRequest) {
  return apiRequest("/auth/login", {
    method: "POST",
    body: JSON.stringify(request),
  });
}
