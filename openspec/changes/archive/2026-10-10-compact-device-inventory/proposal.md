# Compact inventory and console filters
## Why
Inventory controls occupy most of the first screen and an absent risk assessment is displayed as a service failure. Transient gateway downtime also left several duplicate-looking reference errors above the inventory.
## What Changes
- Extract compact search/view controls with inline location/status filters and popover location-search/refresh settings.
- Keep location-grouped device cards visible in a dense responsive board, remove duplicate successful read timestamps and unnecessary one-page navigation.
- Distinguish absent risk evidence from network/access failures and keep recoverable service errors explicit.
- Compact alerts and maintenance filters; reduce alert summary to a small count strip so incidents appear on the first screen.
- Load simulator presets only in simulator context and show availability through an accessible local icon and retry action.
## Impact
Frontend inventory presentation and handling of the existing authorized risk-read 404 response. No device records or backend permissions change.
