# Compact fleet map controls

## Why
The user finds the vehicle button grid and always-visible controls bulky.

## What Changes
Replace the vehicle buttons with a compact selectable table and place existing map options in a collapsible settings panel at the top right. Preserve GPS, track and selection behavior.

## Impact
Frontend fleet presentation only. No API, source, authentication or storage changes.

The vehicle table is initially collapsed behind a compact Vehicles/count toggle. Opening and closing preserves selected vehicle and live map behavior.
