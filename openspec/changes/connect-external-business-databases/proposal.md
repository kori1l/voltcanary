# Change: Connect customer databases with explicit read projection and write contracts

Status: complete target design, proposed for implementation, 2026-11-03. Runtime implementation remains stopped. No paid/live calls are authorized by planning.

## Why

A configurable platform requires explicit executable contracts, efficient optional storage features and safe access to customer-owned data, rather than per-scenario code or implicit import/write behavior.

## What Changes

Normative storage/adapters and import contracts define NATIVE/EXTERNAL/PROJECTED modes. Customer DB remains authoritative; no compulsory full copy, schema migration or per-event remote query. Adapter capabilities describe stable keys, schema fingerprint, pushdown, snapshot/CDC/poll/deletion consistency and verified mutation/CAS/idempotency/status. Write credentials do not automatically qualify writes. A supported remote mutation commits its remote receipt atomically with data; lost replies reconcile the same key. Scope/secret/egress/intersection checks apply to metadata, query, history and output. Connection types without a required capability return explicit unsupported, never fake completeness.

## Dependencies

GX; G0 language and G1 dataset identities. Direct read/adapters can progress with native data ownership. Projections feed G3/G4.

## Authoritative Contracts

[Language](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [imports](../../../docs/target/configurable-operations-imports.md), [acceptance A01–A66](../../../docs/target/configurable-operations-acceptance.md). See the [program](../README.md#configurable-operations-program).

