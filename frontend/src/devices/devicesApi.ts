import { apiRequest } from "../api/client";

export function listDevices() {
  return apiRequest("/devices");
}
