## Context

The existing interval batch issues one raw query, then scans every device's union
of samples for each interval and scans it again per physical epoch/metric. Sources
that are not canonical meters consume database, transport and heap resources.

## Decisions

Plan canonical read windows from renewed registry metadata. Contiguous revisions
with the same physical identity/source share their physical bracketing boundary;
replacements remain separate. Read windows retain the existing one-day padding,
asOf clipping and exclusive physical end, so excluded/conflicting sample counts
remain compatible. Power is selected for extrema; counters only when authoritative.

Merge overlapping/adjacent windows per device/source/metric before a single
parameterized lateral range query. Disjoint ranges avoid row duplication without
deduplicating original measurements. A PostgreSQL covering source/metric/time index
supports bounded lookups; H2 receives a portable index. Invalid quality/value/unit
rows remain available to the unchanged calculator and preserve barrier semantics.

Build a request-local index once, keyed by device/source/metric. Normalize samples
once per stream and retain quality/duplicate/conflict prefix totals. Use binary
bounds for each read window and visit only interval points plus boundary neighbors
instead of filtering/normalizing complete lists per interval/epoch. The existing
gap/reset/integration math consumes these prepared points; full-window quality
diagnostics remain compatible. Single-period comparisons retain their original combined read
window; batch intervals retain their individual windows. Deadline, 64 intervals,
50 devices per interval and 500000 selected rows remain bounded. At most10000
merged canonical ranges keep PostgreSQL bind parameters below its protocol limit.

## Verification

Compare every result field with the original broad SQL plus calculator path on
Timescale fixtures: resets/rollovers, replacements, same-meter revisions, gaps,
bad/conflicting samples, old brackets, comparisons, tenant/null-account isolation,
and overlapping intervals. Trace one measurement SELECT for 1/12/32 intervals and
inspect indexed plans with large unrelated-source history. Rebuild the existing
Docker telemetry service and compare the four demo dashboards with retained data.
