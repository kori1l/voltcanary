# Configurable telemetry field templates

## Why
Owners need reusable rules for selecting stored device metrics and independent control of fields displayed on a map. Fixed adapter code and chart selection cannot provide retention policy.

## What Changes
Add owner-scoped typed field templates, source assignment and bulk assignment by device type. Assigned templates select incoming canonical metrics by bounded dotted path, preserving only selected fields in accepted events and storage. Numeric custom fields become chart series; text and boolean fields appear in telemetry tables. Map presentation selects displayed stored fields independently. Existing sources without a template retain their behavior.

## Impact
Common telemetry contracts, device registry and runtime configuration, ingress, telemetry storage/read APIs, device settings/telemetry and dashboard widget settings. Existing history is retained.
