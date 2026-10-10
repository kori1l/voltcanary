## 1. Presentation
- [x] 1.1 Add minimal defaults, optional annotations and on-demand evidence drawer without suppressing exceptional states.
- [x] 1.2 Consolidate toolbar/screen selector and keep keyboard/touch editing and screen operations.
## 2. Verification
- [x] 2.1 Verify toggles, chart semantics, denied/partial/stale states and navigation with frontend checks.
- [x] 2.2 Verify real browser persistence, desktop/mobile layout, screenshots, documentation and OpenSpec validation/archive.

Evidence: 70 frontend files / 212 tests passed; typecheck/build passed. Focused panel tests cover minimal defaults, opt-in annotations, on-demand drawer/focus, partial/estimate/stale/denied states and gap rendering. Browser confirmed annotation flags survive reload, restored minimal flags, selector switching, info drawer and mobile layout without overflow. Collapsed-navigation desktop panel top 80px; mobile top 144px. Final screenshot artifacts/dashboard-minimal-canvas.jpg contains a real synthetic telemetry plot and value, no permanent caption/footer text. Strict OpenSpec39 and structure checks passed. No AGENTS.md/skill updates required.
