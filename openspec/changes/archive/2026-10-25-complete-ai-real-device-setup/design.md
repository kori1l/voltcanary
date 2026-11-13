# Design

DeviceTaskContext derives explicit choices from the latest answer and persists
them through existing durable PlanStore clarification context. Device compilation
checks the user's original request for device quantity, never concatenated name
answers. Model output is still a bounded intent; server compilation owns setup.

DeviceSetupExecutor owns CREATE_DEVICE. The existing keyed device API provides
the durable creation receipt. HSL setup is enabled separately and uses a fixed
operator-configured private connector URL. A typed HSL identity comes from the
user's text; the model cannot choose URLs or credentials. The connector checks
the caller's current device ownership with ordinary JWT-authenticated APIs,
creates/reuses one API/REST source, assigns an existing compatible template,
issues a source token privately and atomically persists the binding. It never
returns tokens. A serialized bounded binding store prevents duplicate identities
and reloads MQTT subscriptions without Docker control. Replay after uncertain
setup reuses the same device receipt and source binding. Availability is checked
before initial device mutation. Provisioning failures remain UNKNOWN and use
existing explicit reconciliation; waiting for telemetry is a successful connection
with an explicit WAITING_FOR_DATA status, not a claim of measured readiness.

The public owner-authorized status read renews device/source access and reports
actual latest GPS evidence freshness. Dashboard handoff remains the existing
deviceId selection and current capability resolution, with no copied invented
telemetry or modifications to dashboard task/operation schemas.

Connector control is private, has bounded JSON requests and fixed upstreams,
and stores only existing per-source credentials in its ignored binding file.
Read-only root filesystem remains; its private config mount becomes writable
only for reviewed provisioning. Other integrations retain truthful manual setup.
