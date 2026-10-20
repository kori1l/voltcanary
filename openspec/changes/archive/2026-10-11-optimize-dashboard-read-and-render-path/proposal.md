# Optimize dashboard reads and rendering

## Why
The dashboard optimization research confirmed duplicated payloads, idle chart renders, irrelevant read keys, energy interval HTTP N+1 and maintenance SQL N+1. Latest reads scan historical rows; slow batch owners delay useful evidence. The user authorized implementing the research follow-ups in that order.

## What Changes
- Compact query transport with shared scope dictionaries and authorized snapshot details.
- Isolate information clocks, normalize owner projections and deduplication keys.
- Preserve historical latest semantics while improving SQL; batch maintenance devices and business energy intervals.
- Deliver independent panels progressively at a shared asOf; cancel refresh and show layout skeletons.
- Bound cache by bytes with current authorization and business invalidation; compress large gateway JSON.

## Capabilities
### New Capabilities
- `dashboard-query-optimization`: bounded compact and progressive dashboard reads.

## Impact
Dashboard, telemetry, device and gateway services; frontend query/viewer and panel information; dashboard API documentation. Existing energy math, quality, scope and access semantics remain authoritative. Runtime speedups require fresh measurements.
