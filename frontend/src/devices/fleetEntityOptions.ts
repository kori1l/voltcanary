import { listDevices, listLocations } from "./devicesApi";
import { locationLabel } from "./deviceInventoryPresentation";
import type { EntityOptionLoader } from "../components/usePagedEntityOptions";
export const loadDeviceOptions: EntityOptionLoader = async ({ search, offset, limit, signal }) => (await listDevices({ search: search || undefined, offset, limit }, signal)).map((device) => ({ id: device.id, name: device.name, status: device.status }));
export const loadLocationOptions: EntityOptionLoader = async ({ search, offset, limit, signal }) => (await listLocations({ search: search || undefined, offset, limit }, signal)).map((location) => ({ id: location.id, name: locationLabel(location) }));
