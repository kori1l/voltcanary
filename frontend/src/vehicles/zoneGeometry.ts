import type { VehicleZone } from "./monitoringModel";
export function zonePolygons(zones: VehicleZone[]) {
  return zones.map((zone) => {
    const latitude = (zone.latitude * Math.PI) / 180,
      longitude = (zone.longitude * Math.PI) / 180,
      angular = zone.radiusMetres / 6371000;
    const ring = Array.from({ length: 65 }, (_, i) => {
      const bearing = (i / 64) * 2 * Math.PI;
      const lat = Math.asin(
        Math.sin(latitude) * Math.cos(angular) +
          Math.cos(latitude) * Math.sin(angular) * Math.cos(bearing),
      );
      const lon =
        longitude +
        Math.atan2(
          Math.sin(bearing) * Math.sin(angular) * Math.cos(latitude),
          Math.cos(angular) - Math.sin(latitude) * Math.sin(lat),
        );
      return [(lon * 180) / Math.PI, (lat * 180) / Math.PI];
    });
    return {
      type: "Feature" as const,
      properties: { name: zone.name },
      geometry: { type: "Polygon" as const, coordinates: [ring] },
    };
  });
}
