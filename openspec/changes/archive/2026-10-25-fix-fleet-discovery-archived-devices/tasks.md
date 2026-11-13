## 1. Implementation and verification
- [x] 1.1 Add paged fleet discovery excluding decommissioned vehicles before the display cap.
- [x] 1.2 Verify archived-page, truncation, cancellation/error, and existing console behavior.
- [x] 1.3 Verify Docker live telemetry and fleet eligibility for the reported bus; align operational documentation.

Evidence: Docker vehicle tests, typecheck and production
build passed. Inventory discovery reads two pages and returns 21 eligible
vehicles including cd4b5d2f-5f5f-496a-9d51-2789a3b1fb14. Browser verified its
FRESH marker, MOVING state, real coordinates and observed route 92N. Live source
evidence: ignored artifacts/bus-02512-connection-repair.json. The HSL connector
was extended from 16 to 17 bindings, preserving existing bindings and tokens.
