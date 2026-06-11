import { apiRequest } from "../api/client";

export interface RegisterRequest {
  email: string;
  password: string;
}

export interface RegisterResponse {
  userId: string;
  email: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: "Bearer";
  expiresIn: number;
}

export interface MeResponse {
  id: string;
  email: string;
  role: string;
  status: string;
  emailVerified: boolean;
}

export interface MessageResponse {
  message: string;
}

export interface AdminUserResponse {
  id: string;
  email: string;
  role: "USER" | "ADMIN" | "SUPPORT";
  status: "ACTIVE" | "DISABLED" | "LOCKED" | "PENDING_VERIFICATION" | "DELETED";
  emailVerified: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AdminRefreshSessionResponse {
  id: string;
  userId: string;
  createdAt: string;
  expiresAt: string;
  revokedAt: string | null;
  active: boolean;
}

export function register(request: RegisterRequest) {
  return apiRequest<RegisterResponse>("/auth/register", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function login(request: LoginRequest) {
  return apiRequest<LoginResponse>("/auth/login", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function me() {
  return apiRequest<MeResponse>("/auth/me");
}

export function refresh(refreshToken: string) {
  return apiRequest<LoginResponse>("/auth/refresh", {
    method: "POST",
    body: JSON.stringify({ refreshToken }),
  });
}

export function logout(refreshToken: string) {
  return apiRequest<MessageResponse>("/auth/logout", {
    method: "POST",
    body: JSON.stringify({ refreshToken }),
  });
}

export function logoutAll() {
  return apiRequest<MessageResponse>("/auth/logout-all", { method: "POST" });
}

export function changePassword(currentPassword: string, newPassword: string) {
  return apiRequest<MessageResponse>("/auth/change-password", {
    method: "POST",
    body: JSON.stringify({ currentPassword, newPassword }),
  });
}

export function forgotPassword(email: string) {
  return apiRequest<MessageResponse>("/auth/forgot-password", {
    method: "POST",
    body: JSON.stringify({ email }),
  });
}

export function resetPassword(token: string, newPassword: string) {
  return apiRequest<MessageResponse>("/auth/reset-password", {
    method: "POST",
    body: JSON.stringify({ token, newPassword }),
  });
}

export function requestEmailVerification(email: string) {
  return apiRequest<MessageResponse>("/auth/email-verification/request", {
    method: "POST",
    body: JSON.stringify({ email }),
  });
}

export function confirmEmailVerification(token: string) {
  return apiRequest<MessageResponse>("/auth/email-verification/confirm", {
    method: "POST",
    body: JSON.stringify({ token }),
  });
}

export function listAdminUsers() {
  return apiRequest<AdminUserResponse[]>("/auth/admin/users");
}

export function updateAdminUserRole(userId: string, role: AdminUserResponse["role"]) {
  return apiRequest<AdminUserResponse>(`/auth/admin/users/${userId}/role`, {
    method: "PATCH",
    body: JSON.stringify({ role }),
  });
}

export function updateAdminUserStatus(userId: string, status: AdminUserResponse["status"]) {
  return apiRequest<AdminUserResponse>(`/auth/admin/users/${userId}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
}

export function listAdminUserSessions(userId: string) {
  return apiRequest<AdminRefreshSessionResponse[]>(`/auth/admin/users/${userId}/sessions`);
}

export function revokeAdminUserSessions(userId: string) {
  return apiRequest<{ revokedCount: number }>(`/auth/admin/users/${userId}/sessions/revoke`, {
    method: "POST",
  });
}
