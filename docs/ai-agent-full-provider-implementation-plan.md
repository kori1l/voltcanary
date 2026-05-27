# AI Agent Model Integration Plan

## 1. What We Are Building

The goal is to make VoltCanary AI Agent configurable from the frontend.

This is not an AWS/Azure/enterprise cloud integration project. The target is direct integration with LLM model providers:

1. OpenAI / GPT models.
2. DeepSeek models.
3. Anthropic / Claude models.
4. Google Gemini API models.
5. Mistral models.
6. xAI models.
7. Groq-hosted models.
8. OpenRouter as an optional multi-model gateway.
9. Any OpenAI-compatible endpoint, including local or self-hosted gateways.

The user must be able to open the frontend, add a provider, enter an API key once, select a model, test it, and make it the default AI Agent model.

Current problem:

1. `ai-agent-service` currently supports only `rules` and one hardcoded `openai` provider.
2. OpenAI credentials are configured through env/application files.
3. Frontend only shows provider status and does not allow model configuration.
4. There is no UI for changing model, base URL, temperature, token limit, or fallback behavior.

Target result:

1. Credentials are configured through frontend.
2. Secrets are stored encrypted in backend database.
3. Raw secrets are never returned to frontend.
4. The AI Agent can use different model providers through one common internal interface.
5. The user can select GPT, DeepSeek, Claude, Gemini, Mistral, etc. from the UI.
6. OAuth is not required for MVP because most direct model APIs use API keys. If a provider has an OAuth-style flow that is actually useful later, it can be added as an optional extension.

## 2. Product Requirements

### 2.1 Provider settings UI

Add an AI Agent settings screen where a user with admin permissions can:

1. See all configured model providers.
2. Add a new provider.
3. Choose provider type.
4. Enter provider API key.
5. Enter or choose model ID.
6. Configure base URL where needed.
7. Configure model parameters.
8. Test connection.
9. Save provider.
10. Set provider/model as default.
11. Disable provider without deleting it.
12. Rotate API key.
13. Delete provider.

### 2.2 Model parameters configurable from UI

Each provider/model config must support:

1. `modelId`
2. `displayName`
3. `baseUrl`
4. `apiKey`
5. `temperature`
6. `maxOutputTokens`
7. `timeoutMs`
8. `topP` where supported.
9. `responseFormat` / JSON mode where supported.
10. `supportsTools`
11. `supportsJson`
12. `enabled`
13. `default`
14. optional custom headers.

Do not overfit to one provider. Model IDs must be free text with optional curated suggestions.

### 2.3 AI Agent compose screen

Update the current AI Agent page so it has:

1. Active provider/model indicator.
2. Provider selector for the current request.
3. Model selector for the current request.
4. Link/button to model settings.
5. Warning when fallback `rules` planner is active.
6. Error state when configured model fails.
7. Provider/model metadata in the returned plan.

### 2.4 Connection testing

The UI must have a `Test connection` button.

The backend test should:

1. Decrypt API key server-side.
2. Call the selected provider with a tiny safe prompt.
3. Verify the response can be parsed.
4. Verify JSON output support if enabled.
5. Store last test status/message/time.
6. Return a normalized result to frontend.

The test must not expose raw provider response if it contains sensitive data.

## 3. Supported Provider Strategy

### 3.1 Providers to implement first

Implement these first because they are direct model APIs and fit the product requirement:

1. `OPENAI`
   - Direct GPT model provider.
   - Auth: bearer API key.
   - Endpoint style: OpenAI Responses API or Chat Completions fallback.

2. `DEEPSEEK`
   - Direct DeepSeek model provider.
   - Auth: bearer API key.
   - Endpoint style: OpenAI-compatible Chat Completions.
   - Important: handle DeepSeek model IDs as configurable text, not hardcoded.

3. `ANTHROPIC`
   - Direct Claude model provider.
   - Auth: `x-api-key`.
   - Endpoint style: Messages API.

4. `GEMINI`
   - Direct Google Gemini API.
   - Auth: API key.
   - Endpoint style: Gemini generate content endpoint.

5. `MISTRAL`
   - Direct Mistral model provider.
   - Auth: bearer API key.
   - Endpoint style: chat completions.

6. `OPENAI_COMPATIBLE`
   - Generic adapter for any OpenAI-compatible endpoint.
   - Covers DeepSeek-like APIs, local gateways, LiteLLM, Ollama-compatible gateways if exposed as OpenAI-compatible, LM Studio server, vLLM gateway, etc.

### 3.2 Providers to add after MVP

1. `XAI`
2. `GROQ`
3. `OPENROUTER`
4. `OLLAMA_LOCAL`
   - No API key by default.
   - Local base URL.
   - Useful for local models.
5. `CUSTOM_HTTP`
   - Advanced mode for custom request/response mapping.

### 3.3 Providers explicitly out of scope for this plan

Do not build these unless requested later:

1. AWS Bedrock.
2. Azure OpenAI.
3. Google Vertex AI.
4. Enterprise IAM/managed identity integrations.
5. Complex OAuth provider onboarding.

Reason: the current requirement is direct model integration through frontend, not enterprise cloud model platform integration.

## 4. Authentication Decision

### 4.1 MVP auth method

Use API key based configuration.

Flow:

1. User opens frontend settings.
2. User selects provider.
3. User enters API key.
4. Frontend sends API key once to backend over HTTPS.
5. Backend encrypts key and stores it.
6. Frontend never receives the raw key again.
7. UI shows only masked key hint such as `sk-...abcd`.

### 4.2 OAuth

Do not implement OAuth in MVP.

Reason:

1. Direct LLM provider APIs generally use API keys for server-to-server access.
2. OAuth does not remove the need to store a server-side credential/token.
3. OAuth would add complexity without solving the current problem.
4. The user explicitly prefers model configuration through frontend, not enterprise auth complexity.

Add OAuth only later if a specific provider has a useful user-facing flow that maps cleanly to model API access.

## 5. Backend Data Model

Add persistent model provider configuration to `ai-agent-service`.

### 5.1 Table: `ai_model_providers`

Fields:

1. `id uuid primary key`
2. `account_id uuid not null`
3. `created_by_user_id uuid not null`
4. `name varchar(120) not null`
5. `provider_type varchar(64) not null`
6. `status varchar(32) not null`
7. `is_default boolean not null default false`
8. `base_url varchar(512)`
9. `auth_type varchar(32) not null default 'API_KEY'`
10. `masked_secret_hint varchar(80)`
11. `last_test_status varchar(32)`
12. `last_test_message varchar(1024)`
13. `last_test_at timestamptz`
14. `metadata jsonb not null default '{}'`
15. `created_at timestamptz not null`
16. `updated_at timestamptz not null`

Constraints:

1. Unique `(account_id, name)`.
2. Only one default provider per account.

### 5.2 Table: `ai_model_provider_secrets`

Fields:

1. `id uuid primary key`
2. `provider_id uuid not null references ai_model_providers(id)`
3. `secret_type varchar(64) not null`
4. `encrypted_value text not null`
5. `encryption_key_version varchar(64) not null`
6. `created_at timestamptz not null`
7. `rotated_at timestamptz`

Secret types:

1. `API_KEY`
2. `CUSTOM_HEADER_VALUE`

### 5.3 Table: `ai_model_configs`

Fields:

1. `id uuid primary key`
2. `provider_id uuid not null references ai_model_providers(id)`
3. `display_name varchar(160) not null`
4. `model_id varchar(255) not null`
5. `enabled boolean not null default true`
6. `is_default boolean not null default false`
7. `temperature numeric(4,3)`
8. `top_p numeric(4,3)`
9. `max_output_tokens integer`
10. `timeout_ms integer`
11. `supports_tools boolean not null default false`
12. `supports_json boolean not null default true`
13. `response_format varchar(64)`
14. `metadata jsonb not null default '{}'`
15. `created_at timestamptz not null`
16. `updated_at timestamptz not null`

Constraints:

1. Unique `(provider_id, model_id)`.
2. Only one default model per provider.

### 5.4 Table: `ai_agent_model_runs`

Fields:

1. `id uuid primary key`
2. `account_id uuid not null`
3. `user_id uuid not null`
4. `plan_id uuid`
5. `provider_id uuid`
6. `model_config_id uuid`
7. `provider_type varchar(64)`
8. `model_id varchar(255)`
9. `status varchar(32) not null`
10. `prompt_tokens integer`
11. `completion_tokens integer`
12. `total_tokens integer`
13. `latency_ms integer`
14. `error_code varchar(128)`
15. `error_message varchar(1024)`
16. `created_at timestamptz not null`

Purpose:

1. Debug provider failures.
2. Show last runs in UI.
3. Track usage and approximate cost later.

## 6. Secret Storage

Implement `AiSecretEncryptionService`.

MVP:

1. Use AES-GCM.
2. Master key from env var `AI_AGENT_SECRET_ENCRYPTION_KEY`.
3. Key version from `AI_AGENT_SECRET_KEY_VERSION`.
4. Store encrypted value in DB.
5. Store masked hint in provider row.

Required behavior:

1. `createProvider` stores encrypted API key.
2. `updateProvider` does not require resending key.
3. `rotateSecret` replaces encrypted key.
4. `getProvider` returns only masked hint.
5. Logs never include key value.

## 7. Backend API Contract

Add endpoints under `/ai-agent`.

### 7.1 Provider capabilities

```text
GET /ai-agent/model-provider-capabilities
```

Returns provider metadata used by frontend forms.

Example:

```json
[
  {
    "providerType": "DEEPSEEK",
    "displayName": "DeepSeek",
    "authType": "API_KEY",
    "defaultBaseUrl": "https://api.deepseek.com",
    "endpointStyle": "OPENAI_CHAT_COMPLETIONS",
    "supportsCustomBaseUrl": true,
    "supportsJson": true,
    "supportsTools": false,
    "suggestedModels": [
      { "modelId": "deepseek-chat", "displayName": "DeepSeek Chat" },
      { "modelId": "deepseek-reasoner", "displayName": "DeepSeek Reasoner" }
    ]
  }
]
```

### 7.2 Provider management

```text
GET    /ai-agent/model-providers
POST   /ai-agent/model-providers
GET    /ai-agent/model-providers/{providerId}
PUT    /ai-agent/model-providers/{providerId}
DELETE /ai-agent/model-providers/{providerId}
POST   /ai-agent/model-providers/{providerId}/set-default
POST   /ai-agent/model-providers/{providerId}/test
POST   /ai-agent/model-providers/{providerId}/rotate-secret
```

### 7.3 Model management

```text
GET    /ai-agent/model-providers/{providerId}/models
POST   /ai-agent/model-providers/{providerId}/models
PUT    /ai-agent/model-providers/{providerId}/models/{modelId}
DELETE /ai-agent/model-providers/{providerId}/models/{modelId}
POST   /ai-agent/model-providers/{providerId}/models/{modelId}/set-default
POST   /ai-agent/model-providers/{providerId}/models/{modelId}/test
```

### 7.4 Plan request update

Extend current `PlanRequest`:

```json
{
  "message": "Create a fridge simulator",
  "context": {},
  "providerId": "optional uuid",
  "modelConfigId": "optional uuid"
}
```

Behavior:

1. If request specifies provider/model, use it.
2. Else use account default provider/model.
3. Else use `rules` fallback.
4. If provider fails and fallback is enabled, use `rules`.
5. Response should include provider/model actually used.

## 8. Provider Adapter Architecture

Replace the current provider-specific `OpenAiPlanProvider` with a registry.

### 8.1 Interfaces

```text
AiModelProviderAdapter
  providerType()
  endpointStyle()
  defaultBaseUrl()
  suggestedModels()
  testConnection(request)
  createPlan(request)

AiModelProviderRegistry
  adapterFor(providerType)

AiModelProviderResolver
  resolve(accountId, providerId?, modelConfigId?)
```

### 8.2 Common request

```text
AiModelPlanRequest
  accountId
  userId
  message
  context
  toolSchemas
  providerConfig
  decryptedApiKey
  modelConfig
  systemPrompt
  timeoutMs
  correlationId
```

### 8.3 Common response

```text
AiModelPlanResult
  providerType
  modelId
  rawText
  parsedDraft
  promptTokens
  completionTokens
  totalTokens
  latencyMs
```

## 9. Provider Adapter Details

### 9.1 OpenAI adapter

1. Use Responses API as primary path.
2. Support Chat Completions fallback if base URL is OpenAI-compatible but does not support Responses.
3. Auth header: `Authorization: Bearer <apiKey>`.
4. Support `model`, `instructions`, `input`, `max_output_tokens`.
5. Extract output text from Responses API.
6. Extract usage where available.

### 9.2 OpenAI-compatible adapter

1. Use `/chat/completions`.
2. Auth header: `Authorization: Bearer <apiKey>`.
3. Request messages:
   - system prompt;
   - user JSON with message/context.
4. Support response format JSON object when enabled.
5. This adapter should support DeepSeek-like providers if they follow OpenAI-compatible format.

### 9.3 DeepSeek adapter

Option A:

1. Implement as preset over `OPENAI_COMPATIBLE`.
2. Default base URL: DeepSeek API base URL.
3. Suggested models provided by capability registry.

Option B:

1. Separate `DeepSeekPlanProvider` class for provider-specific quirks.
2. Internally reuse OpenAI-compatible request builder.

Recommended MVP: Option A.

### 9.4 Anthropic adapter

1. Use Messages API.
2. Auth header: `x-api-key`.
3. Include provider version header.
4. Convert internal system prompt and user message to Anthropic message format.
5. Extract content text from response.
6. Extract usage fields.

### 9.5 Gemini adapter

1. Use Gemini API key.
2. Convert system prompt and user message to Gemini content format.
3. Use generation config for temperature/topP/max output tokens.
4. Extract text candidates.
5. Handle safety block responses as normalized provider errors.

### 9.6 Mistral adapter

1. Use chat completions endpoint.
2. Auth header: bearer API key.
3. Convert prompt to messages.
4. Extract assistant message content.

### 9.7 Local/OpenAI-compatible models

For local gateways:

1. `apiKey` can be optional.
2. `baseUrl` is required.
3. Timeout should be configurable and usually higher.
4. UI must show that local providers are reachable only from backend container/network.

## 10. Prompt and JSON Contract

Keep a strict JSON planning contract independent of provider.

System prompt must instruct the model:

1. Return JSON only.
2. Do not include markdown.
3. Use only allowed tool names.
4. Never include account ID, tenant ID, admin credentials, DB access, or raw telemetry table access.
5. Do not execute anything.
6. Mutating tools require confirmation.

Expected model output:

```json
{
  "summary": "short summary",
  "explanation": "what will be done",
  "actions": [
    {
      "tool": "CREATE_DEVICE",
      "title": "Create fridge device",
      "description": "Create a fridge in the user's account",
      "parameters": {}
    }
  ]
}
```

Continue using `LlmPlanSanitizer`, but make it provider-independent.

## 11. Frontend Implementation

### 11.1 Files to add

```text
frontend/src/features/aiAgent/
  pages/
    AiAgentPage.tsx
    AiAgentModelSettingsPage.tsx
  components/
    AgentComposePanel.tsx
    AgentPlanPanel.tsx
    AgentExecutionPanel.tsx
    ActiveModelStatus.tsx
    ModelProviderList.tsx
    ModelProviderForm.tsx
    ModelConfigTable.tsx
    ModelConfigForm.tsx
    ProviderTestPanel.tsx
    SecretInput.tsx
  model/
    aiAgent.types.ts
```

If a full feature folder refactor is too large, keep the existing page but add components incrementally.

### 11.2 Provider list UI

Provider card should show:

1. Name.
2. Provider type.
3. Default model.
4. Status.
5. Last test result.
6. Last test time.
7. Masked key hint.
8. Actions:
   - edit;
   - test;
   - set default;
   - rotate key;
   - disable;
   - delete.

### 11.3 Provider form UI

Fields:

1. Provider type select.
2. Display name.
3. Base URL.
4. API key.
5. Default model select/free text.
6. Temperature.
7. Max output tokens.
8. Timeout.
9. JSON mode toggle.
10. Tool support toggle.
11. Custom headers advanced section.

The form must change based on provider capability:

1. OpenAI: base URL optional, API key required.
2. DeepSeek: base URL prefilled, API key required.
3. Anthropic: base URL optional, API key required.
4. Gemini: API key required, base URL usually hidden/advanced.
5. Mistral: API key required.
6. OpenAI-compatible: base URL required, API key optional/required toggle.
7. Local: base URL required, API key optional.

### 11.4 Model config table

Columns:

1. Default.
2. Display name.
3. Model ID.
4. Enabled.
5. JSON.
6. Tools.
7. Temperature.
8. Max tokens.
9. Timeout.
10. Actions.

Allow adding multiple models under one provider.

### 11.5 AI Agent compose UI

Add compact controls above request textarea:

1. Provider select.
2. Model select.
3. Default marker.
4. Settings button.
5. Provider health indicator.

When user creates a plan, send selected `providerId` and `modelConfigId`.

## 12. Authorization

Use simple permissions:

1. Admin users can configure providers/models.
2. Regular users can use enabled default provider/model.
3. Optional later: regular users can choose among enabled models.

Backend must enforce this. Frontend hiding buttons is not enough.

## 13. Safety and Reliability

Required:

1. Validate all model output after provider response.
2. Enforce tool allowlist server-side.
3. Enforce confirmation for mutating tools.
4. Enforce request timeout.
5. Enforce max output tokens.
6. Enforce max input/context size.
7. Add provider call rate limit per user/account.
8. Add fallback to rules when configured model fails and fallback is enabled.
9. Normalize provider errors.
10. Store model run logs without storing full API key or sensitive prompt data.

## 14. Error Codes

Normalize all provider failures:

1. `PROVIDER_NOT_CONFIGURED`
2. `MODEL_NOT_CONFIGURED`
3. `API_KEY_MISSING`
4. `AUTH_FAILED`
5. `MODEL_NOT_FOUND`
6. `RATE_LIMITED`
7. `QUOTA_EXCEEDED`
8. `PROVIDER_TIMEOUT`
9. `PROVIDER_UNAVAILABLE`
10. `INVALID_PROVIDER_RESPONSE`
11. `JSON_PARSE_FAILED`
12. `PLAN_SANITIZATION_FAILED`
13. `CONTEXT_TOO_LARGE`

Frontend should show concise messages and an expandable diagnostics block.

## 15. Implementation Phases

### Phase 1: Backend foundation

1. Add DB migrations for providers, secrets, models, model runs.
2. Add entities/repositories.
3. Add secret encryption.
4. Add provider capability registry.
5. Add provider/model CRUD endpoints.
6. Add connection test endpoint.
7. Add admin authorization checks.

Acceptance:

1. Provider can be created through API.
2. API key is encrypted.
3. Provider response masks API key.
4. Provider can be tested through API.

### Phase 2: Refactor current OpenAI integration

1. Replace env-only `OpenAiPlanProvider` usage with DB-resolved provider/model config.
2. Keep env config only as bootstrap fallback.
3. Add provider/model IDs to `PlanRequest`.
4. Add provider/model metadata to `PlanResponse`.
5. Record model run result.

Acceptance:

1. Existing GPT integration still works.
2. GPT model can be configured from frontend/API.
3. Agent uses selected model.
4. Rules fallback still works.

### Phase 3: Add DeepSeek and OpenAI-compatible support

1. Add `OPENAI_COMPATIBLE` adapter.
2. Add `DEEPSEEK` capability preset.
3. Use same adapter for DeepSeek with provider defaults.
4. Add tests for DeepSeek-style model IDs and base URL.

Acceptance:

1. User can add DeepSeek from UI.
2. User can enter DeepSeek API key.
3. User can select DeepSeek model ID.
4. Agent creates plan using DeepSeek.

### Phase 4: Frontend settings

1. Add AI Agent model settings page.
2. Add provider list.
3. Add provider form.
4. Add model table.
5. Add secret rotation flow.
6. Add test connection UI.
7. Add provider/model selector to current agent page.

Acceptance:

1. User can configure GPT from UI.
2. User can configure DeepSeek from UI.
3. User can switch default model from UI.
4. No file/env editing is needed for normal usage.

### Phase 5: Add Claude, Gemini, Mistral

1. Add Anthropic adapter.
2. Add Gemini adapter.
3. Add Mistral adapter.
4. Add capability presets and suggested model IDs.
5. Add tests for each provider.

Acceptance:

1. Each provider can be configured from UI.
2. Each provider can pass connection test.
3. Each provider can create a valid sanitized plan.

### Phase 6: Quality and operations

1. Add usage/run history UI.
2. Add fallback chain configuration.
3. Add provider-level rate limits.
4. Add metrics:
   - provider requests;
   - latency;
   - token usage;
   - errors;
   - fallback count.
5. Add documentation.
6. Update `.env.example` with only encryption/bootstrap settings.

Acceptance:

1. Admin can see provider health.
2. Admin can see recent model failures.
3. Secrets can be rotated from UI.
4. System degrades to rules fallback when model provider is unavailable.

## 16. Tests

### 16.1 Backend unit tests

1. Secret encryption/decryption.
2. Secret masking.
3. Provider validation.
4. Model validation.
5. Default provider/model selection.
6. OpenAI request mapping.
7. OpenAI-compatible request mapping.
8. DeepSeek preset uses OpenAI-compatible adapter.
9. Anthropic response parsing.
10. Gemini response parsing.
11. Mistral response parsing.
12. Error normalization.
13. Sanitizer blocks invalid tools.

### 16.2 Backend integration tests

1. Create provider.
2. Create model.
3. Set default.
4. Test connection with mocked provider.
5. Create plan using selected model.
6. Rotate secret.
7. Disable provider.
8. Verify fallback to rules.

### 16.3 Frontend tests

1. Provider form changes fields by provider type.
2. API key input is write-only.
3. Provider list shows masked key hint.
4. Test connection result renders correctly.
5. Model table supports default model selection.
6. Agent request sends provider/model IDs.
7. Provider failure state is readable.

## 17. Files Likely To Change

Backend:

1. `backend/services/ai-agent-service/src/main/resources/db/migration/*`
2. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/config/*`
3. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/controller/AiAgentController.java`
4. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/dto/*`
5. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/llm/*`
6. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/service/*`
7. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/model/*`
8. `backend/services/ai-agent-service/src/test/java/com/voltcanary/aiagent/*`

Frontend:

1. `frontend/src/aiAgent/aiAgentApi.ts`
2. `frontend/src/pages/AiAgentPage.tsx`
3. `frontend/src/features/aiAgent/*`
4. `frontend/src/app/styles.css`
5. route configuration in `frontend/src/main.tsx` or app router file.

Deployment/docs:

1. `.env.example`
2. `docker-compose.yml`
3. `docs/api/ai-agent-api.md`
4. `README.md`

## 18. Final MVP Definition

MVP is complete when:

1. User can open frontend AI Agent settings.
2. User can add OpenAI provider and GPT model without editing files.
3. User can add DeepSeek provider and DeepSeek model without editing files.
4. User can test both providers from UI.
5. User can set one as default.
6. User can choose provider/model on AI Agent request.
7. Backend stores API keys encrypted.
8. Frontend never displays raw API keys after save.
9. Agent creates valid plans with selected model.
10. Rules fallback still works.

## 19. Notes Checked Against Provider Docs

Checked on 2026-05-27:

1. OpenAI API authentication uses bearer API keys.
2. DeepSeek documents OpenAI/Anthropic-compatible API access patterns, so an OpenAI-compatible adapter is the right MVP path.
3. Anthropic API uses `x-api-key`.
4. Gemini API supports direct API-key based access for Gemini API usage.

