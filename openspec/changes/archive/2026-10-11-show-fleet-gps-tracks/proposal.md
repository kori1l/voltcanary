# Show recorded GPS tracks on the fleet map

## Why
The common Helsinki map shows current markers only. The user expects the observed movement track on the same map.

## What Changes
Add bounded fifteen-minute recorded history to the authorized fleet position response and draw distinct, selectable trails for every vehicle. Default tracks are visible; a switch hides them. Existing single-vehicle evidence stays compatible.

## Impact
Telemetry fleet dashboard response and frontend fleet map. No fabricated route geometry, historical backfill, schema migration or new polling loop.
