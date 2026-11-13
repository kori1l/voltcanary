# Fix fleet discovery after archived test devices

## Why
The vehicle console reads only the first 101 inventory rows. Archived test
devices consume this limit and hide newly created real vehicles from the map.

## What Changes
- Page through authorized inventory and exclude decommissioned vehicles before
  applying the existing 100-vehicle display and position-batch limit.
- Preserve offline and disabled vehicles, source selection, and explicit errors.
- Verify a new vehicle following more than 100 archived entries is discovered.

## Impact
- Affected spec: vehicle-monitoring-console.
- Frontend fleet discovery only; existing REST contracts remain unchanged.
