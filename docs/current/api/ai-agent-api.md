# AI Agent API

`ai-agent-service` owns the operator assistant planning surface and per-account
model provider configuration. The browser reaches it through the API Gateway
under `/ai-agent/**`; the service runs on port `8088`.

## Current Implementation

Planning and execution:

```http
POST /ai-agent/plans
POST /ai-agent/plans/{planId}/execute
GET /ai-agent/tools
GET /ai-agent/provider
```

Model provider configuration:

```http
GET /ai-agent/model-provider-capabilities
GET /ai-agent/model-providers
POST /ai-agent/model-providers
GET /ai-agent/model-providers/{providerId}
PUT /ai-agent/model-providers/{providerId}
DELETE /ai-agent/model-providers/{providerId}
POST /ai-agent/model-providers/{providerId}/set-default
POST /ai-agent/model-providers/{providerId}/test
POST /ai-agent/model-providers/{providerId}/rotate-secret
GET /ai-agent/model-providers/{providerId}/models
POST /ai-agent/model-providers/{providerId}/models
PUT /ai-agent/model-providers/{providerId}/models/{modelConfigId}
DELETE /ai-agent/model-providers/{providerId}/models/{modelConfigId}
POST /ai-agent/model-providers/{providerId}/models/{modelConfigId}/set-default
```

Requests use the normal gateway-authenticated bearer token. The service parses
the bearer token to scope provider and model configuration to the caller's
account. If no configured account provider is available, `GET /ai-agent/provider`
falls back to environment-level OpenAI settings when present and otherwise to
the rule-based planner.

Provider secrets are write-only after submission or rotation. Responses must
not expose API keys or provider credentials.

## Current Boundaries

The agent may call domain services only through explicit tools and service API
contracts. It does not own device, telemetry, analytics, alert, simulator, or
ML lifecycle data.

Production hardening for managed secrets, auditable tool execution, and
least-privilege service identity is target work documented in
[../../target/production-readiness.md](../../target/production-readiness.md).
