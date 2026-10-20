# Design

The compact wire response shares device/location scope entries and retains primary data, counts, quality and provenance. Full source snapshots are available only with current authorization, matching revision/context/asOf, and bounded retention. Expired details require a refresh.

Owner requests select only the evidence consumed by a widget. Their normalized keys retain account/workspace, authorized IDs, time and relevant projection parameters. Observed metrics and old latest values must survive; a rolling historical cutoff is forbidden.

Business energy uses bounded keyed intervals and the existing calculator, with one renewed scope and shared watermark. Maintenance page device lookup is batched without joining away pagination.

Latest uses a loose observed-metric key scan and indexed candidates for both the
current account and legacy null-account rows. Covering/partial-index SQL lives in
the telemetry Flyway PostgreSQL vendor location; H2 has a compatible ordinary
index. Portable migrations stay in their existing common location.

Progressive responses complete independent panels with the same authorization preparation and asOf. Frontend generations and AbortController prevent obsolete requests; same-context refresh keeps ready panels, changed context clears them. Revisit retains layout only pending renewed access.

Cache entries have byte and TTL bounds, actor/grant isolation and renewed resource checks. Business inputs require current version fingerprints before reuse. Private query responses use no-store headers and progressive delivery flushes each ready panel group.
