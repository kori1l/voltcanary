import { apiRequest } from "../../api/client";

export type CircleGeometry = { longitude: number; latitude: number; radiusMetres: string };
export type CircleRevision = {
  id: string;
  revision: number;
  geometry: CircleGeometry;
  digest: string;
};
export type CircleDependency = {
  id: string;
  kind: "RULE_BUNDLE" | "RULE_DEPLOYMENT";
  ruleId?: string;
  state?: string;
};
type CircleImpact<T> = { circleId: string; revision: number; complete: boolean; dependencies: T[] };
const path = (id: string) => `/data/spatial-features/${encodeURIComponent(id)}/circle`;

export function listCircles(after: string | null, signal: AbortSignal) {
  const query = new URLSearchParams({ limit: "25" });
  if (after) query.set("after", after);
  return apiRequest<{ items: CircleRevision[]; nextAfter: string | null }>(
    `/data/spatial-features/circles?${query}`,
    { signal },
  );
}

export function readCircle(id: string, revision: number, signal: AbortSignal) {
  return apiRequest<CircleRevision>(`${path(id)}?revision=${revision}`, { signal });
}

export function readCircleDependencies(id: string, revision: number, signal: AbortSignal) {
  return apiRequest<CircleImpact<CircleDependency>>(
    `/analytics/configurable-rules/circles/${encodeURIComponent(id)}/dependencies?revision=${revision}`,
    { signal },
  );
}

export function saveCircle(
  id: string,
  expectedRevision: number,
  geometry: CircleGeometry,
  signal: AbortSignal,
) {
  return apiRequest<CircleRevision>(path(id), {
    method: "PUT",
    body: JSON.stringify({ expectedRevision, geometry }),
    signal,
  });
}
