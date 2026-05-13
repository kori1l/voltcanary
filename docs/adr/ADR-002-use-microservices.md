# ADR-002: Use microservice architecture

## Status

Accepted

## Context

VoltCanary needs separate responsibilities for identity, device management, telemetry ingestion, time-series processing, analytics, alerts, and ML/Data Science.

## Decision

Use a microservice-based architecture with clear service boundaries.

## Consequences

Benefits:
- services can scale independently
- ingestion, telemetry, analytics, and ML can evolve separately
- the architecture is close to production IoT systems

Trade-offs:
- more infrastructure complexity
- harder local development
- requires observability and clear contracts
