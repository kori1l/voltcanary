# Tasks

## 1. Regression correction

- [x] 1.1 Reproduce the superseded-load false error and inspect tile/freshness presentation.
- [x] 1.2 Restore default device tiles, retain the optional table and fix current device failure/retry presentation.
- [x] 1.3 Remove repeated healthy timestamps while preserving named stale/refresh evidence.
- [x] 1.4 Verify focused regression tests, actual components in the browser, frontend typecheck/test/build and OpenSpec/structure checks.
- [x] 1.5 Update current guidance and archive only after verification passes.

Verification: frontend 30 files / 84 tests; typecheck/build; OpenSpec/structure and whitespace checks passed. Actual component fixtures verified under StrictMode at 360/768/1280 widths, including error and stale states. See docs/current/flows/console-interface.md.
