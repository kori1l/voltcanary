# Restore recognizable device tiles and truthful load feedback

## Why
The user reports that the new default table makes appliance types harder to recognize. Device details can show a generic context error after a superseded load returns null, and independent successful sections repeat unlabeled refresh timestamps.

## What Changes
- Restore location-grouped icon tiles as the initial inventory view while keeping the table available.
- Derive device-load failures from the current resource state, ignoring superseded responses and clearing failures on recovery.
- Show named refresh evidence when it matters (stale data or refresh in progress), without repeating healthy section timestamps.

## Impact
Frontend-only regression correction using existing APIs. Update device-console-experience and console-data-state-semantics, focused tests and current console guidance. No backend/authentication changes.
