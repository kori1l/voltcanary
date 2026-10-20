## ADDED Requirements

### Requirement: Compact authorized evidence
Dashboard queries SHALL transmit primary evidence once and share scope metadata. Details SHALL retain the original watermark and require current dashboard and resource authorization. Retention SHALL be bounded.

#### Scenario: Open panel information
- **WHEN** an authorized viewer opens information for a retained snapshot
- **THEN** details match its revision, context and asOf; expired or revoked evidence is unavailable

### Requirement: Minimal bounded owner reads
Read keys SHALL depend only on consumed evidence. Energy interval and maintenance device reads SHALL be bounded batches. Latest SHALL retain old observed metrics, account isolation and deterministic tie-breaking without history cutoff.

#### Scenario: Equivalent count widgets
- **WHEN** widgets differ only in unused pagination parameters
- **THEN** they share one owner read

### Requirement: Responsive safe rendering
Idle charts SHALL NOT rerender for information age clocks. Progressive panels SHALL share one asOf and current authorization. Requests SHALL be cancelled on obsolete context; old account or filter data SHALL NOT be presented as current.

#### Scenario: Slow source
- **WHEN** a fast panel completes before a slow independent owner
- **THEN** its evidence can be rendered before the slow panel completes

### Requirement: Bounded valid cache and delivery
Cache reuse SHALL renew authorization, honor force refresh, expire within its bound and invalidate changed business inputs. Dashboard evidence delivery SHALL NOT use public shared caching.

#### Scenario: Changed business facts
- **WHEN** facts or tariffs change between queries
- **THEN** prior business estimates are not reused as current
