# ADR-001: Use monorepo for initial development

## Status

Accepted

## Context

VoltCanary consists of several backend services, frontend, ML service, deployment manifests, and documentation. The project is developed by one person at the initial stage.

## Decision

Use a private monorepo for the initial development phase.

## Consequences

Benefits:
- easier local development
- easier shared Docker Compose setup
- easier CI/CD
- all documentation and deployment files are located near the code

Trade-offs:
- repository can grow large
- service ownership boundaries must be maintained by structure and discipline
