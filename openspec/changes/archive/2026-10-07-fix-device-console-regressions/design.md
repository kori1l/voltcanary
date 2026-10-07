# Design

Keep the existing device icon cards, location grouping and canonical links. Change the initial view to cards without removing the optional table.

useSectionResource intentionally returns null for both failed and superseded loads. useDeviceDetailsData must not translate every null into a new independent generic error. Present the device resource's current error and preserve separate mutation errors. Location lookup failure must not imply that the device is missing.

SectionFeedback suppresses timestamps for idle successful resources by default. Stale/error feedback remains visible, with labeled retrieval evidence for stale data and refresh progress. Explicitly requested successful freshness remains available for consumers that need it. Device-load errors have a named Retry control and successful recovery clears them.

Verify the repeated effect/load sequence under React StrictMode, genuine device failure/recovery, location-only failure, tiles/table switching and stale feedback. Use the existing local UI fixture with StrictMode for visual verification.
