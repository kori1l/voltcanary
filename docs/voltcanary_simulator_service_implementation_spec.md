# VoltCanary — Simulator Service and Unified Telemetry Ingestion Implementation Spec

Version: 0.1  
Status: Draft for implementation  
Target audience: AI coding agent / developer  
Project: VoltCanary  
Feature: Create simulated devices from frontend, manage simulation configs via YAML, generate real telemetry signals through the normal ingestion pipeline.

---

## 0. Purpose

This document describes how to add a simulation subsystem to VoltCanary.

The user must be able to create a mock/simulated device from the frontend as a normal device. The simulator must be implemented as a separate microservice. The simulator must generate real telemetry signals and send them through the normal telemetry ingestion path. The simulator must support editable YAML configuration per simulated device. The simulator must support HTTP first and MQTT next. The ingestion architecture must avoid duplicated services with the same responsibility.

The implementation must support:

- creating a simulated device as a normal `Device`;
- creating a `DataSource` with `sourceType=SIMULATOR`;
- opening a separate `Simulation` tab on the device page;
- editing YAML configuration for a simulator;
- validating and saving YAML;
- starting and stopping simulation;
- generating telemetry points;
- sending telemetry through HTTP to the ingestion service;
- later publishing telemetry via MQTT;
- using the generated telemetry for frontend charts, analytics, alerts, testing, and demos.

---

## 1. Current Architectural Decision

### 1.1 Do not create a separate `mqtt-connector-service`

Do not implement a separate business service called `mqtt-connector-service`.

Reason:

If we create:

```text
ingestion-service
mqtt-connector-service
```

then both services will end up doing the same kind of work:

```text
receive telemetry
resolve source/device
validate payload
map payload to canonical event
apply idempotency
publish telemetry.raw.v1
```

This duplicates responsibility and creates an unclear architecture.

### 1.2 Use one unified telemetry ingestion boundary

The correct architectural decision:

```text
telemetry-ingestion-service
```

This may physically still be the existing `ingestion-service` module. It can be renamed later if needed, but conceptually it must be treated as the unified telemetry input boundary.

It must contain multiple input adapters:

```text
telemetry-ingestion-service
  rest/
    RestTelemetryController
    RestTelemetryMapper

  mqtt/
    MqttTelemetrySubscriber
    MqttTopicParser
    MqttPayloadMapper

  source/
    SourceResolver
    SourceRegistryCache
    SourceTokenValidator

  pipeline/
    TelemetryIngestionPipeline
    IngestionValidator
    PayloadNormalizer
    IdempotencyService

  kafka/
    TelemetryRawPublisher
```

All input adapters must call the same internal pipeline:

```text
HTTP payload -> TelemetryIngestionPipeline -> telemetry.raw.v1
MQTT payload -> TelemetryIngestionPipeline -> telemetry.raw.v1
CSV later    -> TelemetryIngestionPipeline -> telemetry.raw.v1
```

### 1.3 Add a separate `simulator-service`

`simulator-service` is separate because it is not an ingestion service. It is a telemetry producer/source.

It owns:

```text
SimulationConfig
SimulationConfigVersion
SimulationRun
SimulationRunEvent
YAML configuration
simulation lifecycle
telemetry generation
HTTP/MQTT sending
```

It must not:

```text
write directly to TimescaleDB
publish telemetry.raw.v1 directly
calculate quality flags
calculate analytics
own the Device model
duplicate ingestion validation
```

Correct data path:

```text
simulator-service
  -> telemetry-ingestion-service
  -> telemetry.raw.v1
  -> telemetry-service
  -> TimescaleDB
  -> analytics-service
  -> alert-service
  -> frontend
```

---

## 2. Target System Diagram

```text
Frontend
   |
   v
API Gateway
   |
   +---------------------> Device Service
   |                         |
   |                         | owns Device/DataSource
   |
   +---------------------> Simulator Service
                             |
                             | owns SimulationConfig/SimulationRun/YAML
                             |
                             | HTTP mode
                             v
                    Telemetry Ingestion Service
                             |
                             | publishes
                             v
                       telemetry.raw.v1
                             |
                             v
                       Telemetry Service
                             |
                             v
                        TimescaleDB
                             |
                             v
                    Analytics / Alerts / UI
```

MQTT mode later:

```text
Simulator Service
   |
   | publish MQTT
   v
MQTT Broker
   |
   | subscribed by MQTT adapter
   v
Telemetry Ingestion Service
   |
   v
telemetry.raw.v1
```

The MQTT broker is an infrastructure component, not a business service.

---

## 3. Implementation Order

Implement in this order:

```text
A. Stabilize build/tests.
B. Refactor ingestion-service into a unified ingestion pipeline.
C. Add source resolving and source tokens.
D. Extend device-service with SIMULATOR data source support.
E. Add simulator-service domain, DB migrations, and APIs.
F. Add YAML parser and validator.
G. Add telemetry generation engine.
H. Add simulation runner.
I. Add HTTP telemetry sender.
J. Add frontend Simulation tab.
K. Add Docker Compose and Kubernetes changes.
L. Add MQTT adapter/sender as the next phase.
M. Add tests and documentation.
```

Do not start with MQTT before the HTTP simulation path works end-to-end.

---

## 4. Stage A — Stabilize Current Project

Before adding the simulation feature, fix current backend test/build issues.

Known blocker:

```text
FixtureBuilders.java calls AnomalyDetectedEvent with an outdated signature.
The current AnomalyDetectedEvent record requires field `type`.
```

Tasks:

```text
1. Find FixtureBuilders.java.
2. Find all usages of AnomalyDetectedEvent.
3. Add missing field `type`.
4. Run backend tests.
5. Ensure `mvn test` passes.
```

Definition of Done:

```text
mvn test passes
frontend build passes
frontend typecheck passes
ml smoke tests pass
docker compose config --quiet passes
```

Do not add simulator feature while backend tests are red.

---

## 5. Stage B — Unified Telemetry Ingestion Pipeline

### 5.1 Create package structure in existing ingestion-service

Path example:

```text
backend/services/ingestion-service/src/main/java/com/voltcanary/ingestion/
```

Add packages:

```text
pipeline/
rest/
mqtt/
source/
kafka/
validation/
normalization/
idempotency/
```

### 5.2 Create internal canonical DTOs

Create `IncomingTelemetryMessage`:

```java
public record IncomingTelemetryMessage(
    String sourceId,
    Instant timestamp,
    Map<String, Object> metrics,
    IngestionProtocol protocol,
    String rawPayload,
    String correlationId,
    String sourceToken
) {
}
```

Create `IngestionProtocol`:

```java
public enum IngestionProtocol {
    HTTP,
    MQTT,
    CSV,
    WEBHOOK
}
```

Create `ResolvedTelemetryMessage`:

```java
public record ResolvedTelemetryMessage(
    UUID accountId,
    UUID deviceId,
    UUID dataSourceId,
    String externalSourceId,
    Instant timestamp,
    Map<String, Object> metrics,
    IngestionProtocol protocol,
    String correlationId
) {
}
```

Create `IngestionResult`:

```java
public record IngestionResult(
    boolean accepted,
    UUID eventId,
    String message
) {
}
```

### 5.3 Create `TelemetryIngestionPipeline`

```java
public interface TelemetryIngestionPipeline {
    IngestionResult ingest(IncomingTelemetryMessage message);
}
```

Implementation flow:

```text
1. Validate required fields.
2. Resolve source/device/account.
3. Validate data source status and protocol.
4. Normalize payload.
5. Apply idempotency.
6. Build TelemetryReceivedEvent.
7. Publish to telemetry.raw.v1.
8. Return accepted result.
```

### 5.4 Move REST controller logic into pipeline

Current REST ingestion endpoint must become thin:

```text
RestTelemetryController
  -> maps HTTP request to IncomingTelemetryMessage(protocol=HTTP)
  -> calls TelemetryIngestionPipeline.ingest()
  -> returns 202 Accepted or validation error
```

REST controller must not:

```text
call Kafka producer directly
resolve device/source directly
contain idempotency logic
contain normalization logic
```

---

## 6. Stage C — Source Resolving and Source Tokens

### 6.1 Motivation

Both HTTP and MQTT ingestion must resolve telemetry source to:

```text
accountId
deviceId
dataSourceId
sourceType
protocol
status
```

The ingestion service must not call `device-service` on every telemetry message.

For MVP:

```text
1. Try local cache.
2. If cache miss, call device-service internal API.
3. Cache result.
```

Later:

```text
device-service publishes data-source.created/updated/revoked events
ingestion-service consumes them and maintains a local read model
```

### 6.2 Extend `data_sources`

In `device-service`, add fields if missing:

```text
ingestion_token_hash
token_prefix
token_created_at
token_last_used_at
token_revoked_at
```

Also ensure `data_sources` has:

```text
id
device_id
source_type
protocol
external_id
status
credentials_ref
created_at
updated_at
```

### 6.3 Add or update enums

`DataSourceType`:

```java
public enum DataSourceType {
    SMART_PLUG,
    SENSOR,
    SIMULATOR,
    HOME_ASSISTANT,
    CSV,
    MQTT,
    API,
    SHELLY,
    TASMOTA,
    TUYA,
    CUSTOM
}
```

`DataSourceProtocol`:

```java
public enum DataSourceProtocol {
    REST,
    MQTT,
    CSV,
    HTTP_POLLING,
    WEBHOOK,
    HOME_ASSISTANT,
    VENDOR_API
}
```

### 6.4 Add source token generation endpoint

In `device-service`:

```http
POST /devices/{deviceId}/data-sources/{dataSourceId}/token
```

Response:

```json
{
  "token": "vc_src_live_xxxxxxxxxxxxx",
  "tokenPrefix": "vc_src_live_abcd"
}
```

Rules:

```text
Raw token is shown only once.
Only token hash is stored in DB.
tokenPrefix is stored for lookup/diagnostics.
Token can be rotated/revoked.
```

### 6.5 Add internal source lookup API

In `device-service`:

```http
GET /internal/data-sources/by-token-prefix/{tokenPrefix}
GET /internal/data-sources/by-external-id/{externalId}
GET /internal/data-sources/{dataSourceId}
```

Response:

```json
{
  "accountId": "uuid",
  "deviceId": "uuid",
  "dataSourceId": "uuid",
  "externalId": "sim-fridge-001",
  "sourceType": "SIMULATOR",
  "protocol": "REST",
  "status": "ACTIVE",
  "tokenHash": "..."
}
```

### 6.6 Add SourceResolver in ingestion-service

Package:

```text
ingestion/source/
```

Classes:

```text
SourceResolver
SourceRegistryCache
SourceTokenValidator
DeviceServiceSourceClient
ResolvedSource
```

`ResolvedSource`:

```java
public record ResolvedSource(
    UUID accountId,
    UUID deviceId,
    UUID dataSourceId,
    String externalId,
    DataSourceType sourceType,
    DataSourceProtocol protocol,
    SourceStatus status
) {
}
```

### 6.7 HTTP source resolving logic

```text
1. Read Authorization: Bearer <source-token>.
2. Extract token prefix.
3. Find source in local cache.
4. If cache miss, call device-service internal API.
5. Verify raw token against token hash.
6. Check source status ACTIVE.
7. Check protocol REST.
8. Return ResolvedSource.
```

### 6.8 Dev compatibility mode

If current ingestion flow uses user JWT + `deviceId` in body, keep this mode for manual/dev usage:

```text
Mode 1: Device/source token mode — preferred for simulator and real devices.
Mode 2: User JWT + deviceId mode — dev/manual mode.
```

Simulator-service must use source token mode.

---

## 7. Stage D — Device Service Changes for Simulator

### 7.1 Creating simulated devices

A simulated device is a normal `Device`.

Example:

```json
{
  "name": "Kitchen Fridge Simulator",
  "type": "FRIDGE",
  "locationId": "location-1"
}
```

It must have a normal `DataSource`:

```json
{
  "sourceType": "SIMULATOR",
  "protocol": "REST",
  "externalId": "sim-fridge-001"
}
```

### 7.2 Device creation flow

Frontend should create:

```text
1. Device
2. DataSource with sourceType=SIMULATOR
3. Source token
4. Simulation config in simulator-service
```

Do not hide this by writing simulator-specific device model in `simulator-service`.

`device-service` owns:

```text
Device
Location
DataSource
DataSource token
```

`simulator-service` owns:

```text
SimulationConfig
SimulationRun
YAML
```

---

## 8. Stage E — Add Simulator Service

### 8.1 Create module

Path:

```text
backend/services/simulator-service/
```

Add to Maven parent.

Port:

```text
8087
```

### 8.2 Dependencies

Use:

```text
spring-boot-starter-web
spring-boot-starter-data-jpa
spring-boot-starter-validation
spring-boot-starter-actuator
postgresql
flyway-core
jackson-dataformat-yaml
spring-boot-starter-webflux or RestClient
micrometer
```

MQTT later:

```text
org.eclipse.paho.client.mqttv3
or spring-integration-mqtt
```

### 8.3 Package structure

```text
com.voltcanary.simulator
  config/
  controller/
  domain/
  repository/
  service/
  yaml/
  generator/
  runner/
  sender/
  dto/
  exception/
```

### 8.4 Application config

```yaml
spring:
  application:
    name: simulator-service

server:
  port: 8087

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true

app:
  simulator:
    http:
      ingestion-url: ${SIMULATOR_INGESTION_URL:http://ingestion-service:8083/ingestion/readings}
    mqtt:
      enabled: ${SIMULATOR_MQTT_ENABLED:false}
      broker-url: ${SIMULATOR_MQTT_BROKER_URL:tcp://mosquitto:1883}
      topic-template: "voltcanary/sources/{sourceId}/telemetry"
```

---

## 9. Stage F — Simulator Domain Model

### 9.1 Entity: SimulationConfig

Fields:

```text
id UUID
accountId UUID
deviceId UUID
dataSourceId UUID
name String
profile SimulationProfile
protocol SimulationProtocol
yamlConfig TEXT
yamlVersion Integer
status SimulationConfigStatus
createdAt Instant
updatedAt Instant
createdBy UUID
updatedBy UUID
```

### 9.2 Entity: SimulationConfigVersion

Fields:

```text
id UUID
configId UUID
version Integer
yamlConfig TEXT
changeNote String nullable
createdAt Instant
createdBy UUID
```

### 9.3 Entity: SimulationRun

Fields:

```text
id UUID
configId UUID
accountId UUID
deviceId UUID
dataSourceId UUID
status SimulationRunStatus
startedAt Instant
stoppedAt Instant nullable
startedBy UUID
stopReason String nullable
generatedPoints Long
lastError String nullable
```

### 9.4 Entity: SimulationRunEvent

Fields:

```text
id UUID
runId UUID
eventType String
message String
createdAt Instant
```

### 9.5 Enums

```java
public enum SimulationProfile {
    GENERIC_APPLIANCE,
    FRIDGE,
    AIR_CONDITIONER,
    BOILER,
    PUMP,
    HEATER
}
```

```java
public enum SimulationProtocol {
    HTTP,
    MQTT
}
```

```java
public enum SimulationConfigStatus {
    DRAFT,
    ACTIVE,
    INVALID,
    DISABLED
}
```

```java
public enum SimulationRunStatus {
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}
```

### 9.6 Flyway migration

Create:

```text
V001__create_simulation_tables.sql
```

Tables:

```text
simulation_configs
simulation_config_versions
simulation_runs
simulation_run_events
```

---

## 10. Stage G — Simulator Service API

### 10.1 Config API

```http
GET /simulation/devices/{deviceId}/config
PUT /simulation/devices/{deviceId}/config
POST /simulation/devices/{deviceId}/config/validate
POST /simulation/devices/{deviceId}/config/reset-template
GET /simulation/devices/{deviceId}/config/versions
```

### 10.2 Run API

```http
POST /simulation/devices/{deviceId}/start
POST /simulation/devices/{deviceId}/stop
GET /simulation/devices/{deviceId}/status
GET /simulation/devices/{deviceId}/runs
GET /simulation/runs/{runId}
```

### 10.3 Template API

```http
GET /simulation/templates
GET /simulation/templates/{profile}
```

### 10.4 Save config request

```json
{
  "dataSourceId": "uuid",
  "name": "Kitchen Fridge Simulator",
  "profile": "FRIDGE",
  "protocol": "HTTP",
  "yamlConfig": "schemaVersion: 1\nsimulator:\n  ..."
}
```

### 10.5 Validate config response

Valid:

```json
{
  "valid": true,
  "errors": []
}
```

Invalid:

```json
{
  "valid": false,
  "errors": [
    {
      "path": "metrics.activePowerW.cycle.onDuration",
      "message": "min must be less than max"
    }
  ]
}
```

### 10.6 Start response

```json
{
  "runId": "uuid",
  "status": "RUNNING"
}
```

### 10.7 Status response

```json
{
  "status": "RUNNING",
  "runId": "uuid",
  "profile": "FRIDGE",
  "protocol": "HTTP",
  "generatedPoints": 154,
  "startedAt": "2026-05-19T12:00:00Z",
  "lastError": null
}
```

---

## 11. Stage H — YAML Schema v1

### 11.1 YAML parser

Use:

```text
jackson-dataformat-yaml
```

Create:

```text
SimulationYamlParser
SimulationYamlValidator
SimulationConfigModel
MetricConfig
AnomalyConfig
ScheduleConfig
TargetConfig
```

### 11.2 Default YAML template

```yaml
schemaVersion: 1

simulator:
  name: kitchen-fridge-simulator
  profile: FRIDGE
  mode: NORMAL
  protocol: HTTP

target:
  sourceId: "sim-fridge-001"

schedule:
  interval: 5s
  startDelay: 0s
  duration: 30m
  speed: 1.0

metrics:
  voltageV:
    type: constant
    value: 230.0
    noise:
      type: gaussian
      stdDev: 1.5

  rssi:
    type: constant
    value: -61
    noise:
      type: gaussian
      stdDev: 3

  activePowerW:
    type: cycle
    offValue:
      mean: 2.0
      noise: 0.5
    onValue:
      mean: 120.0
      noise: 8.0
    cycle:
      onDuration:
        min: 8m
        max: 16m
      offDuration:
        min: 20m
        max: 45m

  currentA:
    type: derived
    expression: "activePowerW / voltageV"

  energyWh:
    type: accumulator
    from: activePowerW

relayState:
  type: derived
  expression: "activePowerW > 5 ? 'ON' : 'OFF'"

anomalies:
  - type: POWER_SPIKE
    enabled: false
    startAfter: 10m
    duration: 20s
    powerW: 600

  - type: CONSUMPTION_GROWTH
    enabled: false
    startAfter: 15m
    growthPercent: 25
    rampDuration: 10m

  - type: OFFLINE
    enabled: false
    startAfter: 20m
    duration: 5m
```

### 11.3 YAML validation rules

Validate:

```text
schemaVersion exists
schemaVersion is supported
simulator.profile is supported
simulator.protocol is supported
target.sourceId exists
schedule.interval is positive
schedule.duration is positive or absent
metric types are supported
cycle duration min < max
anomaly type is supported
anomaly startAfter is positive
power values are non-negative
```

---

## 12. Stage I — Telemetry Generation Engine

### 12.1 Interfaces

```java
public interface TelemetryGenerator {
    GeneratedTelemetryPoint next(SimulationContext context);
}
```

```java
public record GeneratedTelemetryPoint(
    Instant timestamp,
    String sourceId,
    Map<String, Object> metrics
) {
}
```

```java
public record SimulationContext(
    UUID runId,
    UUID deviceId,
    UUID dataSourceId,
    String sourceId,
    Instant startedAt,
    Instant currentTime,
    long tickNumber,
    SimulationConfigModel config
) {
}
```

### 12.2 Classes to implement

```text
GenericApplianceGenerator
FridgeGenerator
MetricValueGenerator
CycleMetricGenerator
ConstantMetricGenerator
DerivedMetricCalculator
EnergyAccumulator
AnomalyInjector
```

### 12.3 Required MVP modes

```text
NORMAL
POWER_SPIKE
CONSUMPTION_GROWTH
OFFLINE
```

### 12.4 OFFLINE behavior

If OFFLINE anomaly is active, the simulator must stop sending telemetry during the configured window. Do not send fake offline telemetry unless explicitly configured later.

---

## 13. Stage J — Simulation Runner

### 13.1 Use in-memory scheduler for MVP

Create:

```text
SimulationRunner
SimulationRunManager
RunningSimulationRegistry
```

On `POST /start`:

```text
1. Load config.
2. Validate config.
3. Create SimulationRun(status=STARTING).
4. Create ScheduledFuture.
5. Set status=RUNNING.
6. Every interval generate telemetry point.
7. Send point through configured protocol.
8. Increment generatedPoints.
9. Store run event logs.
```

On `POST /stop`:

```text
1. Find running future.
2. Cancel future.
3. Set status=STOPPED.
4. Save stoppedAt and stopReason.
```

### 13.2 Kubernetes limitation

Because the MVP runner is in-memory:

```text
simulator-service replicas: 1
```

Do not add HPA for simulator-service yet.

Later, if scaling is needed:

```text
introduce DB leases/distributed locks
move from in-memory registry to worker-based model
```

---

## 14. Stage K — HTTP Sender

### 14.1 HTTP target

For internal Docker/Kubernetes MVP:

```text
simulator-service -> telemetry-ingestion-service
```

Config:

```yaml
app:
  simulator:
    http:
      ingestion-url: ${SIMULATOR_INGESTION_URL:http://ingestion-service:8083/ingestion/readings}
```

Later, for external-like demo:

```text
simulator-service -> api-gateway -> telemetry-ingestion-service
```

### 14.2 HTTP payload

```json
{
  "sourceId": "sim-fridge-001",
  "timestamp": "2026-05-19T12:30:00Z",
  "metrics": {
    "activePowerW": 120.5,
    "voltageV": 230.2,
    "currentA": 0.52,
    "energyWh": 1523.4,
    "relayState": "ON",
    "rssi": -60
  }
}
```

### 14.3 Authorization

Use source ingestion token:

```http
Authorization: Bearer vc_src_live_xxxxx
```

Do not store raw token in user-editable YAML.

Recommended MVP approach:

```text
YAML contains sourceId.
Raw token is stored separately in simulator config secret field or credential reference.
```

If the project does not yet have secret storage, store raw token in simulator DB only for local MVP, but mark this as non-production and document it.

---

## 15. Stage L — Frontend Changes

### 15.1 Device creation flow

In the create device form, add data source selection:

```text
Data source:
- Manual REST/API
- Simulator
- MQTT
- Home Assistant later
```

If user selects `Simulator`, frontend should:

```text
1. POST /devices
2. POST /devices/{deviceId}/data-sources with sourceType=SIMULATOR
3. POST /devices/{deviceId}/data-sources/{dataSourceId}/token
4. POST /simulation/devices/{deviceId}/config with default YAML
5. Redirect to /devices/{deviceId}?tab=simulation
```

### 15.2 Device details tabs

Add:

```text
Overview | Telemetry | Analytics | Alerts | Simulation
```

### 15.3 Simulation tab components

Create:

```text
SimulationStatusCard
SimulationYamlEditor
SimulationControls
SimulationRunHistory
SimulationProtocolSelector
SimulationTemplateSelector
```

### 15.4 Simulation tab UI

Show:

```text
Status: STOPPED / RUNNING / FAILED
Profile
Protocol
Generated points
Started at
Last error
```

Buttons:

```text
Validate YAML
Save Config
Reset Template
Start
Stop
```

Optional later:

```text
Inject Power Spike
Inject Consumption Growth
Go Offline
```

Quick actions can modify YAML or start with temporary overrides later.

---

## 16. Stage M — API Gateway Changes

Add route:

```text
/simulation/** -> simulator-service
```

Requirements:

```text
JWT required
X-User-Id forwarded
X-User-Email forwarded if used
X-User-Role forwarded
X-Correlation-Id forwarded
```

Important security requirement:

Gateway must remove incoming user-supplied `X-User-*` headers and set them itself after JWT validation.

---

## 17. Stage N — Docker Compose Changes

### 17.1 Add simulator-service

In app profile:

```text
simulator-service
```

Port:

```text
8087:8087
```

Environment:

```text
SPRING_PROFILES_ACTIVE=docker
SIMULATOR_INGESTION_URL=http://ingestion-service:8083/ingestion/readings
SIMULATOR_MQTT_BROKER_URL=tcp://mosquitto:1883
```

### 17.2 Add MQTT broker later

For MQTT stage, add Mosquitto or another broker to infra profile:

```text
mosquitto
```

Port:

```text
1883:1883
```

### 17.3 Health check

Check:

```bash
curl http://localhost:8087/actuator/health
```

---

## 18. Stage O — Kubernetes Changes

### 18.1 Add simulator-service Deployment/Service

Add:

```text
Deployment
Service
ConfigMap
Secret if needed
readinessProbe
livenessProbe
resources
```

Set:

```text
replicas: 1
```

Reason:

```text
in-memory scheduled simulation runs cannot be safely scaled yet
```

### 18.2 Add MQTT broker later

For dev namespace only:

```text
mosquitto Deployment
mosquitto Service
```

Production MQTT setup can be designed later.

### 18.3 Required env vars

```text
SIMULATOR_INGESTION_URL=http://ingestion-service:8083/ingestion/readings
SIMULATOR_MQTT_BROKER_URL=tcp://mosquitto:1883
```

---

## 19. Stage P — MQTT Phase

Implement MQTT after HTTP simulation works.

### 19.1 Add MQTT adapter inside ingestion-service

Do not add a separate service.

Package:

```text
ingestion/mqtt/
```

Classes:

```text
MqttTelemetrySubscriber
MqttTopicParser
MqttPayloadMapper
```

### 19.2 MQTT config

```yaml
app:
  mqtt:
    enabled: false
    broker-url: tcp://localhost:1883
    client-id: voltcanary-ingestion
    topic-filter: voltcanary/sources/+/telemetry
```

### 19.3 Topic convention

```text
voltcanary/sources/{sourceId}/telemetry
```

Example:

```text
voltcanary/sources/sim-fridge-001/telemetry
```

### 19.4 MQTT payload

```json
{
  "timestamp": "2026-05-19T12:30:00Z",
  "metrics": {
    "activePowerW": 120.5,
    "voltageV": 230.2,
    "currentA": 0.52,
    "energyWh": 1523.4,
    "relayState": "ON",
    "rssi": -60
  }
}
```

### 19.5 MQTT adapter flow

```text
MqttTelemetrySubscriber receives message
MqttTopicParser extracts sourceId
MqttPayloadMapper builds IncomingTelemetryMessage(protocol=MQTT)
TelemetryIngestionPipeline.ingest()
```

### 19.6 MQTT MVP security

For MVP:

```text
broker internal-only
source resolves by topic sourceId
source protocol must be MQTT
source status must be ACTIVE
```

Later:

```text
MQTT username/password per source
broker ACL
TLS
token-based MQTT auth
```

### 19.7 MQTT sender in simulator-service

Add:

```text
MqttTelemetrySender
```

If YAML protocol is `MQTT`, runner must use `MqttTelemetrySender`.

---

## 20. Stage Q — Observability

### 20.1 Simulator metrics

Add Micrometer metrics:

```text
simulator_runs_started_total
simulator_runs_stopped_total
simulator_runs_failed_total
simulator_points_generated_total
simulator_http_send_errors_total
simulator_mqtt_publish_errors_total
simulator_active_runs
simulator_tick_duration_seconds
```

### 20.2 Logs

Every simulator log should contain when possible:

```text
runId
deviceId
dataSourceId
sourceId
accountId
protocol
profile
correlationId
```

---

## 21. Stage R — Tests

### 21.1 Unit tests — simulator-service

Add tests for:

```text
YAML parser parses valid YAML
YAML validator rejects invalid profile
YAML validator rejects invalid interval
Fridge generator produces activePowerW
Energy accumulator increases energyWh
Power spike anomaly changes activePowerW
Offline anomaly skips sending
```

### 21.2 Integration tests — simulator-service

Add tests for:

```text
save config
validate config
start run
stop run
send HTTP telemetry to mocked ingestion endpoint
run history is stored
```

### 21.3 Ingestion tests

Add tests for:

```text
REST adapter calls TelemetryIngestionPipeline
SourceResolver resolves valid source token
SourceResolver rejects invalid token
SourceResolver rejects inactive source
TelemetryIngestionPipeline publishes telemetry.raw.v1
```

### 21.4 MQTT tests later

Add tests for:

```text
MqttTopicParser extracts sourceId
MqttPayloadMapper maps payload
MQTT adapter calls TelemetryIngestionPipeline
```

### 21.5 E2E scenario

Create smoke/e2e scenario:

```text
1. register/login
2. create location
3. create device
4. create simulator data source
5. generate source token
6. create simulation config
7. start simulation
8. wait for telemetry
9. query latest reading
10. assert activePowerW exists
11. stop simulation
```

---

## 22. Stage S — Documentation and ADR

### 22.1 Add ADR

Create:

```text
docs/adr/ADR-009-unified-telemetry-ingestion-service.md
```

Content:

```md
# ADR-009: Unified Telemetry Ingestion Service

## Status

Accepted

## Context

VoltCanary supports multiple telemetry input protocols: HTTP, MQTT, CSV and future vendor integrations.
Creating separate services for HTTP ingestion and MQTT connector would duplicate source resolving,
validation, idempotency and canonical event publishing logic.

## Decision

Use a single telemetry-ingestion-service bounded context.
HTTP, MQTT, CSV and future vendor protocols are implemented as input adapters inside this service.
All adapters call the same TelemetryIngestionPipeline and publish canonical TelemetryReceivedEvent
to telemetry.raw.v1.

## Consequences

Benefits:
- one source resolving mechanism
- one idempotency mechanism
- one canonical telemetry event publishing path
- no duplicated ingestion logic
- easier testing and documentation

Trade-offs:
- service becomes larger
- HTTP and MQTT have different scaling profiles
- later we may deploy the same service image in different adapter modes
```

### 22.2 Add simulation docs

Create:

```text
docs/simulation/simulator-service.md
docs/simulation/yaml-schema-v1.md
docs/simulation/frontend-flow.md
docs/architecture/telemetry-ingestion.md
docs/integrations/mqtt-ingestion.md
```

---

## 23. Strict Implementation Constraints

### 23.1 Do not write directly to TimescaleDB from simulator-service

Forbidden:

```text
simulator-service -> TimescaleDB
```

Correct:

```text
simulator-service -> telemetry-ingestion-service -> Kafka -> telemetry-service -> TimescaleDB
```

### 23.2 Do not publish telemetry.raw.v1 directly from simulator-service

Forbidden:

```text
simulator-service -> Kafka telemetry.raw.v1
```

Reason:

The simulator must not bypass ingestion validation, source resolving, token validation, and idempotency.

### 23.3 Do not implement separate mqtt-connector-service

MQTT adapter must be inside telemetry-ingestion-service for this stage.

### 23.4 Do not store raw token in user-editable YAML

YAML is editable and versioned. Source token must be stored separately.

### 23.5 Do not scale simulator-service horizontally yet

Use:

```text
replicas: 1
```

until distributed locks/leases are introduced.

---

## 24. MVP Scope

First deliver only:

```text
1. Unified ingestion pipeline for HTTP.
2. DataSourceType.SIMULATOR.
3. Source token generation.
4. simulator-service with YAML config.
5. HTTP simulation mode.
6. Simulation tab in frontend.
7. Start/stop simulation.
8. Telemetry appears in existing chart.
```

Second phase:

```text
1. MQTT broker in compose.
2. MQTT adapter inside ingestion-service.
3. MQTT sender in simulator-service.
4. protocol=MQTT in YAML.
```

Do not implement Home Assistant, Shelly, Tasmota adapters in this task. Only keep the architecture ready for them.

---

## 25. Definition of Done

The whole feature is complete when:

```text
backend mvn test passes
frontend build/typecheck passes
simulator-service starts in Docker Compose
simulator-service has health endpoint
API Gateway routes /simulation/** to simulator-service
a normal device can be created with sourceType=SIMULATOR
source token can be generated
Simulation tab exists in frontend
YAML config can be viewed
YAML config can be validated
YAML config can be saved
simulation can be started
simulation can be stopped
simulator-service sends HTTP readings to ingestion-service
ingestion-service publishes telemetry.raw.v1
telemetry-service stores readings in TimescaleDB
frontend shows generated telemetry on chart
simulation run history is available
basic simulator-service tests exist
ingestion pipeline tests exist
ADR-009 exists
simulation YAML documentation exists
Docker Compose is updated
Kubernetes manifests are updated
```

---

## 26. Recommended Commit Plan

Use small commits:

```text
1. fix-tests-anomaly-event-fixture
2. refactor-ingestion-to-unified-pipeline
3. add-source-token-and-simulator-data-source-type
4. add-simulator-service-domain-and-api
5. add-simulator-yaml-parser-and-validator
6. add-http-simulation-runner
7. add-simulation-frontend-tab
8. add-compose-and-k8s-for-simulator-service
9. add-mqtt-adapter-skeleton
10. add-docs-and-adr-for-simulation-architecture
```

---

## 27. Final Architecture Summary

Correct model:

```text
Telemetry producers:
- simulator-service
- Home Assistant later
- Shelly later
- Tasmota later
- real smart plug later
- CSV importer later

Input boundary:
- telemetry-ingestion-service

Storage:
- telemetry-service

Analytics:
- analytics-service

Alerts:
- alert-service
```

The simulator is a source of telemetry, not an ingestion service. The ingestion service is the single boundary for all incoming telemetry. All telemetry paths must converge into `telemetry.raw.v1`.

---

## 28. First Manual Demo Scenario

After implementation, this scenario must work:

```text
1. Open frontend.
2. Register/login.
3. Create location.
4. Create device: "Kitchen Fridge Simulator".
5. Select data source: Simulator.
6. Open Device Details.
7. Open Simulation tab.
8. Validate default YAML.
9. Start simulation.
10. Open Telemetry tab.
11. See activePowerW chart updating.
12. Stop simulation.
13. Open Simulation tab.
14. See run history.
```

Optional next demo:

```text
1. Enable POWER_SPIKE anomaly in YAML.
2. Start simulation.
3. Wait for spike.
4. Recalculate analytics.
5. See anomaly/risk/recommendation.
6. See alert created.
```
