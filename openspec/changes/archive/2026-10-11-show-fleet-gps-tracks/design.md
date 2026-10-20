# Design

After all device and source checks, PositionReads returns typed Fleet evidence: existing fleet latest values plus tracks (History per vehicle). Each history interval is intersected with the dashboard frame and the last 15 minutes ending at min(to, asOf). Existing 1000 point per vehicle bound and truncated flag are preserved, at most 20 vehicles. No extra frontend requests are issued.

FleetMap receives optional histories. A pure GeoJSON mapper uses existing geographic continuity rules (break after missing time/impossible jumps), producing colored recorded segments per vehicle plus faint straight gap connectors. Two retained MapLibre layers draw casing and track; selection highlights its line. Show tracks defaults on when histories exist, track period and recorded sample count are visible. The user requested pale straight connectors instead of empty gaps; connectors join the last point before and first after each missing interval without asserting an observed path. No GPS rows means a clear unavailable message, not an invented route. Map sources/layers update without recreating the map. Fit all includes tracks when shown. Old marker-only fleet page remains valid.

Test bounded time/history, authorization-before-read, geometry gaps and selection, rendering toggle; verify actual Helsinki dashboard has visible trails, then run surface checks and update current docs.
