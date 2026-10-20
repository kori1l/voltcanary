# Connect HSL night buses

## Why
The user wants active nighttime transport on the existing common Helsinki map and verification using real observations.

## What Changes
Extend the existing anonymous HFP adapter and idempotent provisioning to explicit bus bindings alongside retained trams. Discover five active buses from routes 97N, 570N, 600 and 30, subject to actual availability. Keep the existing twenty-device bound and use bus icons. Store the same accepted GPS and journey context.

## Impact
Connector, provisioning script, transport marker and operations documentation. No new backend API or database migration.
