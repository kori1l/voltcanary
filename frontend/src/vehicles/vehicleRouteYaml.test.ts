import {it,expect} from "vitest";
import {routeDraft,updateRouteYaml} from "./vehicleRouteYaml";
const yaml="schemaVersion: 1\nsimulator:\n  profile: VEHICLE_GPS\ngps:\n  routeId: CITY_LOOP\n  speedKmh: 36\nmetrics:\n  latitude:\n    type: generated\n# preserve comment\n";
it("adds route and metrics as a draft without discarding unrelated YAML/comments",()=>{
  const draft=routeDraft(yaml);const next=updateRouteYaml(yaml,{...draft,vehicleMetrics:true,waypoints:[{latitude:1,longitude:2,stopSeconds:30},{latitude:2,longitude:3,stopSeconds:0}]});
  expect(next).toContain("# preserve comment");expect(next).toContain("profile: VEHICLE_GPS");expect(next).toContain("ignition:");expect(routeDraft(next).waypoints[0].stopSeconds).toBe(30);
  const disabled=updateRouteYaml(next,{...draft,vehicleMetrics:false});expect(disabled).not.toContain("ignition:");expect(disabled).not.toContain("waypoints:");
});
