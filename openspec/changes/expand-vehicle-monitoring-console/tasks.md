## 1. Contracts and simulation
- [ ] 1.1 Add bounded custom route/stops and optional vehicle metric YAML, preserving old templates and appliance profiles.
- [ ] 1.2 Generate route/stop/ignition/fuel/odometer evidence with monotonic time and test validation/geometry/restart.
- [ ] 1.3 Extend accepted catalogs/projection/DTO with nullable vehicle fields and portable migration.

## 2. Monitoring APIs
- [ ] 2.1 Add authorized bounded fleet latest endpoint with fail-closed device/source checks.
- [ ] 2.2 Add ascending recording cursor endpoint, immutable to/max31days/max2000 and boundary tests.
- [ ] 2.3 Persist bounded owner monitoring settings/zones, authenticate writes and test invalid/foreign access.

## 3. Console
- [ ] 3.1 Add Vehicles navigation and retained fleet map/list/search/status/source selection.
- [ ] 3.2 Add bounded historical paging, observed trip reports and discontinuity tests.
- [ ] 3.3 Add recorded-sample playback/seek/speed/trip selection and CSV export.
- [ ] 3.4 Add zone/settings editor and entry/exit/overspeed evidence with no invented transitions across gaps.
- [ ] 3.5 Add route/stop/vehicle metric controls to the existing explicit simulation draft/save/start workflow.
- [ ] 3.6 Verify mobile, fallback, poll cancellation, errors, source context and zero/null semantics.

## 4. Acceptance
- [ ] 4.1 Run backend migration/API/unit checks and frontend typecheck/test/build.
- [ ] 4.2 Run real fleet/history/playback/settings/custom-route acceptance and leave demo running.
- [ ] 4.3 Align current docs/OpenSpec/indexes, validate structure/specs; archive only complete verified change.
