## Context
The compact workspace still shows a second actions row, screen tabs, normal state paragraphs and provenance below charts. User explicitly requested removing this text by default.
## Goals / Non-Goals
Show useful visualization first; make titles, legends, calculation text, comparisons, axes and inline details configurable. Preserve multi-screen navigation, editing and evidence uncertainty. No data/query algorithm changes.
## Decisions
Use one header with screen selector and primary commands. Screen creation/settings remain explicit icon controls with accessible names. Panel flags in existing presentation JSON default false for title/legend/calculation/comparison/details and true for axes. Existing explicit flags are honored. A compact information control opens full source/provenance in the right drawer even when inline details are hidden. Shared telemetry renderer gets optional caption/axis props, keeping defaults for other pages. Stale/partial/estimated/denied/unavailable states remain compact visible badges. Normal recorded/received text is absent by default. Hover/focus exposes editor controls; touch keeps them available.
## Risks / Trade-offs
Reduced text must not silently turn unknown evidence into zero or hide stale/partial estimates. Preserve semantic labels and plot descriptions for assistive technology. Verify opt-in annotation persistence and unaffected query/access behavior.
## Migration Plan
No definition migration. Missing presentation toggles use minimal defaults.
