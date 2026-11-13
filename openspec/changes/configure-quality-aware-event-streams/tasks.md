# Implementation and Verification

- [ ] 2.1 Implement normative conversion/checkpoint/completeness contracts and verify source-type ambiguity, strict imports, missing deletes and partial capture.

- [ ] 1.1 Define generic observation/interval schemas, source capability mapping and bounded capture policies.
- [ ] 1.2 Implement generic durable accepted-event/coverage pipeline alongside existing latest measurements.
- [ ] 1.3 Implement typed mapping preview and source/entity association with versioned receipts.
- [ ] 1.4 Test transition loss, outages, unknown values, replay, equal timestamps and interval corrections.
- [ ] 1.5 Measure capture limits and align implemented APIs/events/operations with focused checks.


- [ ] 3.1 Integrate typed conversion chains and source eligibility with A52 and sealed mapping revision tests.

## 103. Bus scenario expressibility and evidence

- [ ] 103.1 Implement/prove operational capture and canonical occurrence-context routing across next-stop changes; latest-only5s GPS cannot satisfy a2s absence coverage contract (BUS02/BUS03/BUS05).

## 4. Source owner prerequisites for G0

- [x] 4.1 Implement and verify immutable source schema journals, stable field identities, signed scoped describe, transactional revision publication and conservative capture capabilities on H2/PostgreSQL.
- [x] 4.2 Publish sanitized immutable schema pins from the real source journal, carry only server-resolved pins on accepted telemetry and prove client/runtime mapping cannot forge binding identity; schema delivery alone must not qualify capture.
- [x] 4.3 Persist generic typed accepted observations and exact event replay/conflict outcomes independently of latest-position/time coalescing, with explicit UNKNOWN upstream coverage and H2/PostgreSQL transaction/concurrency evidence.
- [x] 4.4 Persist real input generation identity and explicit checkpoint/time cutover atomically with first generation-bearing observations; preserve old journal/receipts without inventing generation for historical facts, and verify rollback/restart/concurrency on H2/PostgreSQL and Kafka.
- [x] 4.5 Expose actual generation/journal readiness through a fixed internal telemetry owner read and freshly authorized signed source capture description; keep upstream coverage/clock/negative-evidence/runtime readiness conservative and prove scope/drift/failure fences.
- [x] 4.6 Implement bounded exact-generation/checkpoint observation prefix reads through private telemetry and freshly authorized signed source APIs, preserving equal timestamps, schema/cutover fences, byte limits and UNKNOWN quality without claiming sealed snapshots or runtime readiness.
- [x] 4.7 Reject incomplete accepted input receipts during journal reads. Verify the digest format and absence of a pending payload on H2 and PostgreSQL.
- [x] 4.8 Expose signed mapping previews bound to the current source schema. Verify exact token conversion, shared VCL token validation and stale-schema rejection.
- [x] 4.9 Confirm reviewed TEXT-to-BOOLEAN mappings with a revision fence and immutable device-scoped receipt. Apply the published conversion in ingestion and verify accepted-event replay after a schema change.
- [x] 4.10 Replace an existing reviewed conversion and replay a raw field against an exact historical schema revision. Verify old receipt replay and reject corrupted conversion history on H2 and PostgreSQL.
- [x] 4.11 Archive each new schema revision's normalization policy and replay a full raw metrics object using the live ingestion normalizer. Verify historical output, device association and corruption rejection without republishing telemetry.
- [x] 4.12 Persist authenticated producer positions with accepted observations. Verify same-time distinct positions through ingress, gap detection and recovery, exact event replay, epoch isolation and Kafka delivery. Keep upstream coverage UNKNOWN.
- [x] 4.13 Accept trusted interval attestations on a distinct internal authorization boundary. Verify recovery after a missing position, immutable proof replay and fail-closed re-evaluation after contradictory observations on H2 and PostgreSQL.
- [x] 4.14 Read coverage evidence through the signed source owner. Verify fresh source access and grant, exact snapshot and generation, interval containment and the required journal checkpoint against the captured cut.
- [x] 4.15 Add a bounded operator attestor for an authoritative closed producer prefix. Pin its immutable evidence and owner generation, persist retry identity without credentials, renew the signed owner read after submission and reject redirect, schema drift, revoke and changed proof reuse.
- [x] 4.16 Connect a synthetic qualified source's closed producer log to the operator attestor. Prove ingestion-to-owner delivery, gaps, restart and contradictory late observations in a Docker acceptance path before enabling RULE finalization.
- [ ] 4.17 Qualify a production connector's authoritative completeness log and its lifecycle guarantees before claiming coverage for that connector.

Candidate selected 2026-11-07: existing REST source with a full producer
journal. Its authenticated journal endpoint and closure/lifecycle contract
must be identified and verified against the actual source before checking 4.17.
The current REST ingestion endpoint and synthetic producer log are not that
independent evidence.

Synthetic Docker acceptance 2026-11-06: `python scripts/source-coverage-docker-smoke.py`
passed twice against Compose after a cold-start wait adjustment. The second run
wrote the closed producer prefix before ingestion, delivered positions 1 and 3,
observed UNKNOWN through restart, delivered position 2 to reach COVERED, then
delivered a late contradictory position and observed UNKNOWN again through the
signed owner read. The synthetic fixture proves the contract path; it does not
qualify an existing production adapter or enable RULE finalization.
The post-coverage full backend reactor passes 1738 tests across 16 modules
without failures/errors (artifacts/g1-g4-full-backend-20261106.log). The CLI's
six unit cases, strict OpenSpec 104/104, structure and Compose checks pass.

Mapping preview checkpoint 2026-11-05. The combined focused G1/G3 suite passed
159 tests across common-vcl and device-service, with H2 and Docker PostgreSQL.
The reviewed token profile now has confirmed runtime application and device
association. The combined device, ingestion and telemetry reactor passed 117
targeted tests, including concurrent confirmation and H2/PostgreSQL replay.
The owner now replays a raw field with its original conversion. The 50 focused
source owner tests verify two revisions, the historical result and receipt
integrity. Full raw metrics can now be normalized under the original snapshot
through the signed owner read path. The focused VCL, device and ingestion
reactor passed 105 tests. Broader conversions, associations and upstream
coverage remain open.

Observation-read checkpoint2026-11-05: full backend `mvn test` passes1697 tests
across16 modules, with no failures/errors/skips
(artifacts/g3-owner-read-backend-tests-final.log). Shared closed page contracts,
H2/PostgreSQL journal reads and actual V018/V019→V020 upgrade, signed source HTTP
authority races, analytics fixed-head reads and the real Kafka/Timescale pipeline
pass. Canonical-payload checksums bind newly accepted facts; missing legacy pins
remain unavailable without backfill. Equal-time events, contiguous checkpoints,
row/byte limits, mixed-schema/generation rejection and valid-looking payload
corruption are covered. Structure, VCL and OpenSpec checks pass. Broad capture/
mapping/conversion/coverage and temporal qualification tasks remain open.

Verified2026-11-04:22 signed owner cases run on H2/PostgreSQL, including actual
shared HTTP transport/codec round trips, concurrent updates, rollback, historical
pins, revoked authority and missing/corrupt journals. Six analytics adversarial
HTTP cases plus7 native adapter cases pass. The source increment's full backend
reactor first passed1138 tests; the subsequent rule-identity checkpoint passes1156
across16 modules, with no failures/errors/skips. A final device reactor passes221
cases (147 device and74 shared) after the exact stored-configuration fingerprint
fix. Adjacent legacy integers above2^53, unknown unit mapping and remove/readd
field identity are covered. Structure, VCL documentation and OpenSpec104/104 pass. Logs remain ignored in
artifacts/g3-binding-*. This increment does not complete normalized observation
delivery, reviewed conversions/mapping, coverage or temporal execution.

Accepted-input checkpoint2026-11-04: source owner now passes26 H2/PostgreSQL
cases, including actual sanitized envelope/runtime publication, authored-pin
replacement and atomic outbox rollback. Common wire compatibility/pin tests and
ingestion validation/HTTP tests pass. V018/accepted-boolean-text-1 passes30 H2/
PostgreSQL contracts for exact schema recovery, replay/conflict, identical races,
same-timestamp observations, unknown values, quotas, deletion, explicit source-selection
boundaries and atomic receipt/checkpoint/outbox rollback. A real Redpanda/Timescale
test proves raw→PENDING→schema→recovery→outbox→Kafka, preserving replay and both
same-time transitions. The legacy PostgreSQL duplicate INSERT cannot roll back
the second accepted fact under this selected profile.

Final full backend:1250 tests across16 modules, no failures/errors/skips;
artifacts/g3-observation-backend-tests.log. Structure, OpenSpec104/104 and VCL
documentation checks pass; Compose config, local Kustomize render and bootstrap
shell/PowerShell parsing pass. Capture remains disabled by default and requires
explicit binding selection. Schema consumer groups derive from that selection
to replay retained schema revisions after configuration changes. All upstream
coverage/clock qualification and RULE readiness claims remain conservative.
Broad tasks1.*,2.1,3.1,103.1 remain open for reviewed capture/mappings, exact numeric
conversion, qualified coverage/gaps/intervals, limits and runtime evidence.

Generation/readiness checkpoint2026-11-04: V019 and accepted-boolean-text-2
persist a separate input generation and checkpoint/time cutover in the first
successful capture transaction. Legacy profile1 facts/receipts remain unchanged
with no fabricated generation. SQL rollback, identical/distinct concurrent inputs,
component reconstruction from persisted SQL, generation foreign keys and real
Kafka publication/replay are verified. Component reconstruction is not a full
process-crash qualification. The private token-protected telemetry readiness read
and signed source capture description distinguish disabled/schema/input pending
from journal readiness, refresh actual source/account authority after remote I/O,
and never assert qualified upstream clocks, negative evidence or RULE readiness.
The clean focused regression passes88 cases; full backend `mvn clean test` passes
1323 tests across16 modules, zero failures/errors/skips
(artifacts/g3-capture-backend-clean-tests.log). The signed analytics adapter pins
actual generation/cutover and a captured head; head advancement is allowed,
regression and scope/schema/generation drift fail closed. This is not a sealed
row snapshot, runtime activation or upstream continuity proof.
