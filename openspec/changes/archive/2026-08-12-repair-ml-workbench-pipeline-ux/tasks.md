# Tasks

## 1. OpenSpec And Docs

- [x] 1.1 Add the corrective OpenSpec change before implementation.
- [x] 1.2 Update dataset workbench architecture docs to reflect pipeline-first
  layout and automatic preview behavior.
- [x] 1.3 Decide whether project skills need frontend guidance updates.

## 2. Backend Preview Timeline

- [x] 2.1 Add maintenance/failure outcome event markers to preview timeline
  buckets.
- [x] 2.2 Preserve existing preview response compatibility.
- [x] 2.3 Add analytics-service tests for timeline event markers.

## 3. Frontend Pipeline And Workbench UX

- [x] 3.1 Add page-level workflow mode and pipeline step rail in ML Settings.
- [x] 3.2 Replace native multi-select device scope with a readable checklist.
- [x] 3.3 Add automatic debounced preview after valid scope/range/family input.
- [x] 3.4 Keep explicit preview refresh/retry controls.
- [x] 3.5 Make timeline readable in idle/loading/stale/ready states with legend,
  candidate overlays, selected range, gap markers, and event markers.
- [x] 3.6 Move workbench primary actions/status into the first viewport and keep
  metric matrix/commit controls readable below.
- [x] 3.7 Surface downstream readiness in the workbench and pipeline rail.

## 4. Tests And Verification

- [x] 4.1 Add focused frontend tests for auto preview, selector, timeline events,
  stale refresh, and workflow rail status.
- [x] 4.2 Run `npm run openspec:validate`.
- [x] 4.3 Run analytics-service targeted tests.
- [x] 4.4 Run frontend `typecheck`, `test`, and `build`.
