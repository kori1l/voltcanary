# Simulation YAML Schema v1

Required fields:

- `schemaVersion: 1`
- `simulator.profile`
- `simulator.protocol`
- `target.sourceId`
- `schedule.interval`
- `metrics`

Example:

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
  duration: 30m
metrics:
  activePowerW:
    type: cycle
  voltageV:
    type: constant
    value: 230
```

Raw source tokens are not stored in YAML. Local simulator configuration stores
the token separately from the YAML body so templates, versions, logs, and
frontend previews do not expose collector credentials. Target production-like
simulation should use source credential references or injected secrets rather
than persisted raw tokens.
