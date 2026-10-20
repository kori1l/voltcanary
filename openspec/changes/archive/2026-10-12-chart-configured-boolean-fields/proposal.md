# Chart configured boolean telemetry

## Why
Configured boolean values are stored but absent from graph discovery, preventing users from selecting door state.

## What Changes
Project configured booleans to state series (false=0, true=1), advertise boolean metadata and support their selection. Render step segments with explicit state labels or bounded envelope ranges when chronology is unavailable. Backfill real stored configured boolean observations on PostgreSQL, without inventing missing values. Retain raw boolean JSON and keep states out of automatic numeric analytics.

## Impact
Telemetry projection/read metadata, a database migration, frontend selection/chart rendering, contracts and real HSL verification.
