# Design

Use existing offset/limit inventory reads in pages of 100. Keep distinct vehicles
whose status is not DECOMMISSIONED. Stop at inventory exhaustion or the 101st
eligible vehicle, returning at most 100 and the existing truncation indicator.
Pass the component AbortSignal to every page and stop on failure. Source reads
and authorized position batches retain their current behavior and bounds.

Offset pagination uses the existing inventory consistency guarantees; it is not
a snapshot across concurrent inventory edits. Deduplication avoids repeated IDs.
