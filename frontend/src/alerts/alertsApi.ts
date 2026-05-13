import { apiRequest } from "../api/client";

export function listAlerts() {
  return apiRequest("/alerts");
}
