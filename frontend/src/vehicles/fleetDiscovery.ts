import { listDevices, type Device } from "../devices/devicesApi";

const PAGE_SIZE = 100;
const DISPLAY_LIMIT = 100;

export async function discoverFleet(
  signal: AbortSignal,
): Promise<{ items: Device[]; capped: boolean }> {
  const found = new Map<string, Device>();
  for (let offset = 0; ; offset += PAGE_SIZE) {
    signal.throwIfAborted();
    const page = await listDevices({ type: "VEHICLE", limit: PAGE_SIZE, offset }, signal);
    for (const device of page) {
      if (device.status !== "DECOMMISSIONED") found.set(device.id, device);
      if (found.size > DISPLAY_LIMIT)
        return { items: [...found.values()].slice(0, DISPLAY_LIMIT), capped: true };
    }
    if (page.length < PAGE_SIZE) return { items: [...found.values()], capped: false };
  }
}
