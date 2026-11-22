import { parseDocument } from "yaml";
export interface RouteWaypoint {
  latitude: number;
  longitude: number;
  stopSeconds: number;
}
export interface VehicleRouteDraft {
  waypoints: RouteWaypoint[];
  vehicleMetrics: boolean;
  initialFuelPercent: number;
  initialOdometerKm: number;
  fuelTankLitres: number;
  consumptionLitresPer100Km: number;
}
export function routeDraft(yaml: string): VehicleRouteDraft {
  const doc = parseDocument(yaml);
  if (doc.errors.length) throw new Error("Fix YAML syntax before editing the route.");
  const gps = (doc.toJS({ maxAliasCount: 100 }) as { gps?: Record<string, unknown> })?.gps;
  if (!gps) throw new Error("Vehicle GPS configuration is required.");
  return {
    waypoints: Array.isArray(gps.waypoints) ? gps.waypoints : [],
    vehicleMetrics: gps.vehicleMetrics === true,
    initialFuelPercent: Number(gps.initialFuelPercent ?? 80),
    initialOdometerKm: Number(gps.initialOdometerKm ?? 0),
    fuelTankLitres: Number(gps.fuelTankLitres ?? 50),
    consumptionLitresPer100Km: Number(gps.consumptionLitresPer100Km ?? 8),
  };
}
export function updateRouteYaml(yaml: string, draft: VehicleRouteDraft): string {
  const doc = parseDocument(yaml);
  if (doc.errors.length) throw new Error("Fix YAML syntax before editing the route.");
  for (const [key, value] of Object.entries(draft)) {
    if (key === "waypoints" && !draft.waypoints.length) doc.deleteIn(["gps", key]);
    else doc.setIn(["gps", key], value);
  }
  for (const metric of ["ignition", "fuelPercent", "odometerKm"]) {
    if (draft.vehicleMetrics) doc.setIn(["metrics", metric], { type: "generated" });
    else doc.deleteIn(["metrics", metric]);
  }
  return doc.toString();
}
