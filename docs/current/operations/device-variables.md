# Device variable operation and verification

Variables reuse device, ingestion, telemetry, analytics, alert and dashboard
services. Deploy schema and consumers before enabling mutations. Internal service
tokens and device-service URIs must match between services.

| Environment flag | Default | Purpose |
| --- | --- | --- |
| VARIABLE_ACTIVATION_ENABLED | false | Permit enabling source bindings |
| VARIABLE_PROCESSING_ENABLED | false | Ingestion pinning and telemetry materialization/projection |
| VARIABLE_ALERTS_ENABLED | false | Accepted-measurement rule consumer |

Compose and Kubernetes provision the processing, accepted-measurement and DLQ
topics. The processing topic is compacted. Each ingestion replica needs its own
processing group; telemetry and analytics use shared groups with database-backed
receipts. Recovery polls immutable device bundles, so Kafka compaction cannot
remove the only copy of a referenced historical formula.

Rollback first disables variable alerts and new activation, then stops processing
consumers. Keep schema, immutable bundles, lineage and outbox records. Pending
configured events must resume against their pinned generation after re-enabling;
never reinterpret them as legacy unconfigured events.

## Isolated smoke

Run from the repository root with Docker, Java and Maven available:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/device-variable-smoke.ps1 -StartStack -Build
```

Add `-DockerServices` for the complete Docker runtime: Java 21 containers for
identity, gateway, device, ingestion, telemetry, analytics, alert and dashboard,
plus the built console served by nginx. This mode checks real registration/login
and an authenticated gateway descriptor read. Additional ports are 18080
(gateway), 18081 (identity), 18089 (dashboard) and 13000 (console). The standard
mode runs five Java processes on the host against Docker infrastructure.

The fixture owns the `voltcanary-variable-smoke` Compose project: PostgreSQL on
15432, TimescaleDB on 15433, Redpanda on 18092 and five Java services on
18082–18086. It uses separate databases and local test credentials. It does not
connect to external notification providers. Summary and logs are ignored under
`artifacts/`. With `-KeepRunning`, retain processes for diagnosis; use `-Stop` to
stop only recorded fixture-owned processes and remove its containers/volumes.

The executable acceptance creates two devices/sources sharing one type variable,
previews 235 × 0.1 − 0.8 = 22.7, activates bindings, ingests real Kafka events,
checks stored values and bucketed history, creates a source-bound threshold,
checks the normal alert path and verifies duplicate acceptance. A run is evidence
only when its summary reports passed; merely starting the fixture is not success.

Focused Java tests cover ownership, revisions, retention, defaults, rollouts,
projection conflicts, numeric failures, replay, backfill, strict dependency retry
and maintenance. Console tests cover stale preview and response races.
## Recovery, replicas and access acceptance

Keep the complete fixture running to execute the additional scenarios in order:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/device-variable-smoke.ps1 -StartStack -Build -DockerServices -KeepRunning
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/device-variable-recovery-smoke.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/device-variable-replica-smoke.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/device-variable-access-smoke.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/device-variable-smoke.ps1 -Stop
```

Recovery verifies restart, replay, revision pinning, backfill, maintenance and
typed dashboard reads. Replica acceptance uses Redis, a second ingestion
instance on 18093 and a temporary telemetry instance on 18094. It checks shared
deduplication, failover and pending recovery across a disabled processing period.
Access acceptance checks real gateway identities, forged context rejection,
source substitution, cached authorization revocation and side-effect-free reads.
Each script must produce a passed JSON summary under `artifacts/`.

The stop command removes fixture services, one-off replicas, infrastructure and
volumes, including application-profile containers. It leaves Docker Desktop
running; stop Desktop separately when requested, after checking no other work
needs its engine. On Windows request shutdown with
`& "$env:ProgramFiles\Docker\Docker\DockerCli.exe" -Shutdown`, then verify
Desktop/backend processes and Docker WSL distributions have stopped. A zero
command exit alone does not prove shutdown. In the recorded run Desktop needed
its remaining Docker processes stopped and its `docker-desktop` and
`docker-desktop-data` distributions terminated after fixture cleanup.

See [verification evidence and fixture cost limits](../flows/device-variable-verification.md).
This fixture does not establish a production capacity claim. Automatic
lineage/bundle cleanup is a future extension; retain pinned bundles needed by
historical readings and pending messages.
