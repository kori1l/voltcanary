# AI Agent Direct Model Integration: Implementation Steps for Coding Agent

## 0. Task Summary

Implement frontend-configurable direct LLM model integration for `ai-agent-service`.

Current state:

1. Backend has `ai-agent-service`.
2. Current providers: `rules` fallback and env-configured OpenAI only.
3. OpenAI credentials are configured through `application.yml` / env vars.
4. Frontend page `frontend/src/pages/AiAgentPage.tsx` has no provider/model settings.

Required end state for MVP:

1. User can configure OpenAI/GPT from frontend.
2. User can configure DeepSeek from frontend.
3. API keys are stored encrypted in backend DB.
4. User can add/edit/test/delete providers.
5. User can add/select model configs.
6. User can choose provider/model on AI Agent request.
7. Agent uses selected/default provider/model.
8. `rules` fallback still works.

Do not implement AWS, Azure, Vertex, Bedrock, OAuth, or enterprise identity in this task.

## 1. Read First

Before editing, inspect these files:

1. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/controller/AiAgentController.java`
2. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/service/ActionPlanner.java`
3. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/service/AiAgentOrchestrator.java`
4. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/service/PlanStore.java`
5. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/llm/OpenAiPlanProvider.java`
6. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/llm/LlmPlanProvider.java`
7. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/llm/LlmPlanSanitizer.java`
8. `backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/config/AiAgentProperties.java`
9. `backend/services/ai-agent-service/src/main/resources/application.yml`
10. `frontend/src/pages/AiAgentPage.tsx`
11. `frontend/src/aiAgent/aiAgentApi.ts`
12. `frontend/src/main.tsx`
13. `frontend/src/app/styles.css`
14. `docker-compose.yml`

## 2. Implementation Order

Implement in this order. Do not start with frontend.

1. Backend persistence and encryption.
2. Backend provider/model APIs.
3. Provider adapter registry.
4. OpenAI and DeepSeek/OpenAI-compatible execution path.
5. Plan request provider/model selection.
6. Frontend API client.
7. Frontend settings UI.
8. Frontend agent page provider/model selector.
9. Tests and Docker verification.

## 3. Backend Step 1: Add Persistence

### 3.1 Add dependencies if missing

Check `backend/services/ai-agent-service/pom.xml`.

Ensure it has:

1. Spring Data JPA.
2. Flyway.
3. PostgreSQL driver.
4. Validation.

If `ai-agent-service` currently has no DB support, add the same dependency style used by other Spring services in this repo.

### 3.2 Add Flyway migration

Create:

```text
backend/services/ai-agent-service/src/main/resources/db/migration/V001__create_ai_agent_model_provider_tables.sql
```

Tables:

1. `ai_model_providers`
2. `ai_model_provider_secrets`
3. `ai_model_configs`
4. `ai_agent_model_runs`

SQL shape:

```sql
create table ai_model_providers (
  id uuid primary key,
  account_id uuid not null,
  created_by_user_id uuid not null,
  name varchar(120) not null,
  provider_type varchar(64) not null,
  status varchar(32) not null,
  is_default boolean not null default false,
  base_url varchar(512),
  auth_type varchar(32) not null default 'API_KEY',
  masked_secret_hint varchar(80),
  last_test_status varchar(32),
  last_test_message varchar(1024),
  last_test_at timestamptz,
  metadata jsonb not null default '{}',
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint uq_ai_model_providers_account_name unique (account_id, name)
);

create table ai_model_provider_secrets (
  id uuid primary key,
  provider_id uuid not null references ai_model_providers(id) on delete cascade,
  secret_type varchar(64) not null,
  encrypted_value text not null,
  encryption_key_version varchar(64) not null,
  created_at timestamptz not null,
  rotated_at timestamptz,
  constraint uq_ai_model_provider_secrets_provider_type unique (provider_id, secret_type)
);

create table ai_model_configs (
  id uuid primary key,
  provider_id uuid not null references ai_model_providers(id) on delete cascade,
  display_name varchar(160) not null,
  model_id varchar(255) not null,
  enabled boolean not null default true,
  is_default boolean not null default false,
  temperature numeric(4,3),
  top_p numeric(4,3),
  max_output_tokens integer,
  timeout_ms integer,
  supports_tools boolean not null default false,
  supports_json boolean not null default true,
  response_format varchar(64),
  metadata jsonb not null default '{}',
  created_at timestamptz not null,
  updated_at timestamptz not null,
  constraint uq_ai_model_configs_provider_model unique (provider_id, model_id)
);

create table ai_agent_model_runs (
  id uuid primary key,
  account_id uuid not null,
  user_id uuid not null,
  plan_id uuid,
  provider_id uuid,
  model_config_id uuid,
  provider_type varchar(64),
  model_id varchar(255),
  status varchar(32) not null,
  prompt_tokens integer,
  completion_tokens integer,
  total_tokens integer,
  latency_ms integer,
  error_code varchar(128),
  error_message varchar(1024),
  created_at timestamptz not null
);

create index idx_ai_model_providers_account on ai_model_providers(account_id);
create index idx_ai_model_configs_provider on ai_model_configs(provider_id);
create index idx_ai_agent_model_runs_account_created on ai_agent_model_runs(account_id, created_at desc);
```

Postgres partial unique indexes for defaults are recommended:

```sql
create unique index uq_ai_model_provider_default_per_account
  on ai_model_providers(account_id)
  where is_default = true;

create unique index uq_ai_model_default_per_provider
  on ai_model_configs(provider_id)
  where is_default = true;
```

## 4. Backend Step 2: Add Domain Types

Create package:

```text
backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/provider
```

Add enums:

```java
public enum AiModelProviderType {
    OPENAI,
    DEEPSEEK,
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    GEMINI,
    MISTRAL,
    XAI,
    GROQ,
    OPENROUTER,
    OLLAMA_LOCAL
}
```

```java
public enum AiModelProviderStatus {
    ACTIVE,
    DISABLED,
    TEST_FAILED
}
```

```java
public enum AiModelAuthType {
    NONE,
    API_KEY
}
```

```java
public enum AiModelSecretType {
    API_KEY
}
```

Create JPA entities:

1. `AiModelProvider`
2. `AiModelProviderSecret`
3. `AiModelConfig`
4. `AiAgentModelRun`

Create repositories:

1. `AiModelProviderRepository`
2. `AiModelProviderSecretRepository`
3. `AiModelConfigRepository`
4. `AiAgentModelRunRepository`

Repository methods needed:

```java
List<AiModelProvider> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
Optional<AiModelProvider> findByIdAndAccountId(UUID id, UUID accountId);
Optional<AiModelProvider> findByAccountIdAndDefaultTrue(UUID accountId);

List<AiModelConfig> findByProviderIdOrderByCreatedAtAsc(UUID providerId);
Optional<AiModelConfig> findByIdAndProviderId(UUID id, UUID providerId);
Optional<AiModelConfig> findByProviderIdAndDefaultTrue(UUID providerId);

Optional<AiModelProviderSecret> findByProviderIdAndSecretType(UUID providerId, AiModelSecretType secretType);
```

Use naming compatible with the repo's Spring/JPA style.

## 5. Backend Step 3: Secret Encryption

Create:

```text
backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/provider/AiSecretEncryptionService.java
```

Behavior:

1. Read env/config:
   - `app.ai.secrets.encryption-key`
   - `app.ai.secrets.key-version`
2. Use AES-GCM.
3. Store encoded payload as base64.
4. Include IV and ciphertext.
5. Provide:

```java
EncryptedSecret encrypt(String plainText);
String decrypt(String encryptedValue);
String mask(String plainText);
```

Mask examples:

1. `sk-abc123xyz789` -> `sk-...z789`
2. short value -> `****`

Add config defaults in `application.yml`:

```yaml
app:
  ai:
    secrets:
      encryption-key: ${AI_AGENT_SECRET_ENCRYPTION_KEY:}
      key-version: ${AI_AGENT_SECRET_KEY_VERSION:v1}
```

If encryption key is missing and user tries to save a secret, return a clear server error:

```text
AI_AGENT_SECRET_ENCRYPTION_KEY is required to store model provider secrets
```

Do not silently store plaintext.

## 6. Backend Step 4: DTOs

Create DTOs under existing `dto` package or new provider DTO package.

Required DTOs:

1. `ModelProviderCapabilityResponse`
2. `SuggestedModelResponse`
3. `ModelProviderResponse`
4. `CreateModelProviderRequest`
5. `UpdateModelProviderRequest`
6. `RotateModelProviderSecretRequest`
7. `TestModelProviderResponse`
8. `ModelConfigResponse`
9. `CreateModelConfigRequest`
10. `UpdateModelConfigRequest`

Validation:

1. provider name required, max 120.
2. provider type required.
3. API key required on create for API-key providers, except `OLLAMA_LOCAL`.
4. base URL required for `OPENAI_COMPATIBLE` and `OLLAMA_LOCAL`.
5. temperature between 0 and 2.
6. topP between 0 and 1.
7. timeout between 1000 and 120000 ms.
8. max output tokens between 128 and 32000.

Never include raw API key in response DTOs.

## 7. Backend Step 5: Capability Registry

Create:

```text
AiModelProviderCapabilityRegistry
```

It returns hardcoded capabilities.

Initial capabilities:

1. `OPENAI`
   - display: `OpenAI`
   - defaultBaseUrl: `https://api.openai.com/v1`
   - authType: `API_KEY`
   - suggested models: `gpt-5.4-mini`, `gpt-5.4`, `gpt-5.3-codex`
   - supportsJson: true
   - supportsTools: true

2. `DEEPSEEK`
   - display: `DeepSeek`
   - defaultBaseUrl: `https://api.deepseek.com`
   - authType: `API_KEY`
   - suggested models: `deepseek-chat`, `deepseek-reasoner`
   - endpointStyle: `OPENAI_CHAT_COMPLETIONS`

3. `OPENAI_COMPATIBLE`
   - display: `OpenAI-compatible`
   - baseUrl required
   - apiKey optional/required flag
   - model free text

4. `ANTHROPIC`
   - display: `Anthropic Claude`
   - suggested models should be configurable text; include a few examples only.

5. `GEMINI`
   - display: `Google Gemini API`
   - suggested models configurable text.

6. `MISTRAL`
   - display: `Mistral`
   - suggested models configurable text.

Even if adapters for Anthropic/Gemini/Mistral are not implemented in MVP, exposing disabled capabilities is optional. For MVP, prefer only OpenAI, DeepSeek, OpenAI-compatible.

## 8. Backend Step 6: Provider Management Service

Create:

```text
AiModelProviderService
```

Methods:

```java
List<ModelProviderResponse> list(AuthenticatedUser user);
ModelProviderResponse create(AuthenticatedUser user, CreateModelProviderRequest request);
ModelProviderResponse update(AuthenticatedUser user, UUID providerId, UpdateModelProviderRequest request);
void delete(AuthenticatedUser user, UUID providerId);
ModelProviderResponse setDefault(AuthenticatedUser user, UUID providerId);
ModelProviderResponse rotateSecret(AuthenticatedUser user, UUID providerId, RotateModelProviderSecretRequest request);
TestModelProviderResponse test(AuthenticatedUser user, UUID providerId);
```

Rules:

1. Use `user.accountId()` if available. If `AuthenticatedUser` only has user ID today, extend it carefully based on JWT claims used elsewhere.
2. Provider operations must be account-scoped.
3. Only admin should create/update/delete providers. If no fine-grained roles exist in this service, add a small role check from JWT claims.
4. On create:
   - save provider;
   - encrypt secret;
   - create default model if request includes model.
5. On update:
   - do not erase existing secret if request has no API key.
6. On delete:
   - cascade deletes secrets/models.
7. On set default:
   - unset other default providers for same account.
8. On rotate:
   - replace encrypted secret;
   - update masked hint.

## 9. Backend Step 7: Model Config Service

Create:

```text
AiModelConfigService
```

Methods:

```java
List<ModelConfigResponse> list(AuthenticatedUser user, UUID providerId);
ModelConfigResponse create(AuthenticatedUser user, UUID providerId, CreateModelConfigRequest request);
ModelConfigResponse update(AuthenticatedUser user, UUID providerId, UUID modelConfigId, UpdateModelConfigRequest request);
void delete(AuthenticatedUser user, UUID providerId, UUID modelConfigId);
ModelConfigResponse setDefault(AuthenticatedUser user, UUID providerId, UUID modelConfigId);
TestModelProviderResponse testModel(AuthenticatedUser user, UUID providerId, UUID modelConfigId);
```

Rules:

1. Provider must belong to user's account.
2. Only enabled models can be default.
3. On set default, unset other default models for provider.
4. Delete default model only if another model becomes default or provider has no models left.

## 10. Backend Step 8: Controller Endpoints

Update `AiAgentController` or add `AiModelProviderController`.

Endpoints:

```text
GET    /ai-agent/model-provider-capabilities
GET    /ai-agent/model-providers
POST   /ai-agent/model-providers
GET    /ai-agent/model-providers/{providerId}
PUT    /ai-agent/model-providers/{providerId}
DELETE /ai-agent/model-providers/{providerId}
POST   /ai-agent/model-providers/{providerId}/set-default
POST   /ai-agent/model-providers/{providerId}/test
POST   /ai-agent/model-providers/{providerId}/rotate-secret

GET    /ai-agent/model-providers/{providerId}/models
POST   /ai-agent/model-providers/{providerId}/models
PUT    /ai-agent/model-providers/{providerId}/models/{modelConfigId}
DELETE /ai-agent/model-providers/{providerId}/models/{modelConfigId}
POST   /ai-agent/model-providers/{providerId}/models/{modelConfigId}/set-default
POST   /ai-agent/model-providers/{providerId}/models/{modelConfigId}/test
```

Keep existing:

```text
POST /ai-agent/plans
POST /ai-agent/plans/{planId}/execute
GET  /ai-agent/tools
GET  /ai-agent/provider
```

But update `/ai-agent/provider` to return real active default model status.

## 11. Backend Step 9: Refactor LLM Provider Abstraction

Replace env-only `OpenAiPlanProvider` usage with model-provider registry.

Create package:

```text
backend/services/ai-agent-service/src/main/java/com/voltcanary/aiagent/llm/adapter
```

Interfaces/classes:

```java
public interface AiModelAdapter {
    AiModelProviderType providerType();
    TestModelProviderResponse test(AiResolvedModel model);
    LlmPlanDraft plan(AiModelPlanRequest request);
}
```

```java
public record AiResolvedModel(
    AiModelProvider provider,
    AiModelConfig modelConfig,
    String apiKey
) {}
```

```java
public record AiModelPlanRequest(
    UUID accountId,
    UUID userId,
    String message,
    Map<String, Object> context,
    String systemPrompt,
    AiResolvedModel resolvedModel
) {}
```

```java
public class AiModelAdapterRegistry {
    AiModelAdapter adapterFor(AiModelProviderType providerType)
}
```

## 12. Backend Step 10: Implement Adapters

### 12.1 Rules adapter

Keep current rules behavior in `ActionPlanner`.

Do not break fallback.

### 12.2 OpenAI adapter

Refactor existing `OpenAiPlanProvider`.

Required changes:

1. It must accept provider config and model config from DB.
2. It must use decrypted API key from `AiResolvedModel`.
3. It must use provider base URL from DB.
4. It must use model ID from DB.
5. It must use model config timeout/max tokens/temperature.
6. It must return `LlmPlanDraft`.

Keep current Responses API output parsing if working.

### 12.3 OpenAI-compatible adapter

Implement `/chat/completions`.

Request shape:

```json
{
  "model": "model-id",
  "messages": [
    { "role": "system", "content": "..." },
    { "role": "user", "content": "{\"message\":\"...\",\"context\":{}}" }
  ],
  "temperature": 0.2,
  "max_tokens": 1800,
  "response_format": { "type": "json_object" }
}
```

Response extraction:

```text
choices[0].message.content
```

### 12.4 DeepSeek adapter

Implement as wrapper/preset over OpenAI-compatible adapter.

Options:

1. Separate class `DeepSeekPlanProvider` delegates to OpenAI-compatible adapter.
2. Or registry maps `DEEPSEEK` to OpenAI-compatible adapter with default base URL.

Prefer separate class for clearer test coverage, but reuse request building.

## 13. Backend Step 11: Model Resolver

Create:

```text
AiModelResolver
```

Resolve logic:

1. If `PlanRequest.providerId` and `modelConfigId` are present:
   - verify both belong to account;
   - verify model belongs to provider.
2. If only providerId:
   - use provider default model.
3. If no providerId:
   - use account default provider.
   - then provider default model.
4. If no configured provider/model:
   - return empty and allow rules fallback.
5. If provider disabled:
   - reject or fallback based on request/default policy.
6. Decrypt API key only after provider/model validation.

## 14. Backend Step 12: Update PlanRequest and Planning Flow

Update `PlanRequest`:

```java
public record PlanRequest(
    @NotBlank String message,
    Map<String, Object> context,
    UUID providerId,
    UUID modelConfigId
) {}
```

Update `ActionPlanner`:

1. Try configured provider/model through `AiModelResolver`.
2. If resolved:
   - call adapter;
   - parse/sanitize plan;
   - record model run success/failure.
3. If not resolved or adapter fails:
   - fallback to existing rules planner.
4. Preserve current safety notes and action sanitization.

Update `PlanResponse`:

Add optional:

```java
String providerType;
String providerName;
String modelId;
UUID providerId;
UUID modelConfigId;
boolean fallbackUsed;
```

Update frontend types later.

## 15. Backend Step 13: Provider Status

Update `/ai-agent/provider`.

Return:

```json
{
  "provider": "DEEPSEEK",
  "providerName": "DeepSeek production",
  "model": "deepseek-chat",
  "enabled": true,
  "fallback": "rules",
  "configured": true,
  "lastTestStatus": "SUCCESS"
}
```

If no default provider:

```json
{
  "provider": "rules",
  "model": "rule-based planner",
  "enabled": false,
  "fallback": "rules",
  "configured": false
}
```

## 16. Frontend Step 1: Extend API Client

Update:

```text
frontend/src/aiAgent/aiAgentApi.ts
```

Add types and functions:

1. `ModelProviderCapability`
2. `ModelProvider`
3. `CreateModelProviderRequest`
4. `UpdateModelProviderRequest`
5. `ModelConfig`
6. `CreateModelConfigRequest`
7. `UpdateModelConfigRequest`
8. `TestModelProviderResponse`

Functions:

```ts
listModelProviderCapabilities()
listModelProviders()
createModelProvider(request)
updateModelProvider(providerId, request)
deleteModelProvider(providerId)
setDefaultModelProvider(providerId)
testModelProvider(providerId)
rotateModelProviderSecret(providerId, apiKey)

listModelConfigs(providerId)
createModelConfig(providerId, request)
updateModelConfig(providerId, modelConfigId, request)
deleteModelConfig(providerId, modelConfigId)
setDefaultModelConfig(providerId, modelConfigId)
testModelConfig(providerId, modelConfigId)
```

Update `createAgentPlan` signature:

```ts
createAgentPlan(
  message: string,
  context: Record<string, unknown>,
  providerId?: string,
  modelConfigId?: string
)
```

## 17. Frontend Step 2: Add Settings Route

Find router setup in `frontend/src/main.tsx`.

Add route:

```text
/ai-agent/settings
```

Create page:

```text
frontend/src/pages/AiAgentSettingsPage.tsx
```

Keep page directly in `pages` for MVP unless project already has a feature-folder routing pattern.

## 18. Frontend Step 3: Settings Page UI

`AiAgentSettingsPage` must:

1. Load capabilities.
2. Load providers.
3. Show provider list.
4. Show create/edit form.
5. Show model configs for selected provider.
6. Allow test/set-default/delete.

Recommended layout:

```text
[Header: AI Model Settings] [Back to AI Agent]

[Provider list left] [Provider details right]

Provider details:
  - provider config form
  - secret rotation
  - test result
  - models table
  - add model form
```

Avoid a marketing/hero layout. This is an admin settings screen.

## 19. Frontend Step 4: Provider Form

Fields:

1. Provider type select.
2. Name.
3. Base URL.
4. API key.
5. Default model ID.
6. Display name for default model.
7. Temperature.
8. Max output tokens.
9. Timeout.
10. JSON mode checkbox.

Behavior:

1. Provider type changes default base URL.
2. DeepSeek pre-fills base URL.
3. OpenAI pre-fills base URL.
4. OpenAI-compatible requires base URL.
5. API key field is empty when editing existing provider.
6. Show masked key hint separately.
7. Save button disabled if required fields missing.

## 20. Frontend Step 5: Models Table

For selected provider:

1. List model configs.
2. Show default model.
3. Add model.
4. Edit model.
5. Delete model.
6. Test model.
7. Set default.

Use compact table/card style consistent with existing app.

## 21. Frontend Step 6: Update AI Agent Page

Update `frontend/src/pages/AiAgentPage.tsx`.

Add state:

```ts
const [providers, setProviders] = useState<ModelProvider[]>([]);
const [models, setModels] = useState<ModelConfig[]>([]);
const [selectedProviderId, setSelectedProviderId] = useState("");
const [selectedModelConfigId, setSelectedModelConfigId] = useState("");
```

On load:

1. Load provider status.
2. Load providers.
3. Select default provider.
4. Load selected provider models.
5. Select default model.

Add controls above request:

1. Provider select.
2. Model select.
3. Settings link to `/ai-agent/settings`.

On submit:

```ts
setPlan(await createAgentPlan(message, context, selectedProviderId || undefined, selectedModelConfigId || undefined));
```

Update plan display to show provider/model actually used if response includes it.

## 22. Frontend Step 7: Styles

Update `frontend/src/app/styles.css`.

Add classes:

1. `.agent-settings-layout`
2. `.agent-provider-list`
3. `.agent-provider-card`
4. `.agent-provider-card-active`
5. `.agent-provider-form`
6. `.agent-model-table`
7. `.agent-model-row`
8. `.agent-secret-hint`
9. `.agent-test-result`
10. `.agent-model-selector`

Keep settings UI dense and operational.

## 23. Deployment Config

Update:

1. `.env.example`
2. `docker-compose.yml`
3. `backend/services/ai-agent-service/src/main/resources/application.yml`

Add:

```text
AI_AGENT_SECRET_ENCRYPTION_KEY=
AI_AGENT_SECRET_KEY_VERSION=v1
```

Generate a local dev value example, but do not commit a real production key.

For local Docker, the key must be present or provider saving should fail clearly.

## 24. Tests

### 24.1 Backend tests

Add or update tests:

1. Secret encryption masks and decrypts.
2. Create provider stores encrypted API key.
3. Provider response does not return API key.
4. Create model config.
5. Set default provider/model.
6. OpenAI-compatible adapter parses mock response.
7. DeepSeek adapter uses OpenAI-compatible endpoint style.
8. Planner uses selected provider/model.
9. Planner falls back to rules if no provider configured.
10. Provider test handles auth failure.

### 24.2 Frontend build

Run:

```powershell
cd frontend
npm run build
```

### 24.3 Backend build/test

Run targeted backend tests first:

```powershell
cd backend
mvn -pl services/ai-agent-service -am test
```

If too slow, at least run:

```powershell
cd backend
mvn -pl services/ai-agent-service -am -DskipTests package
```

### 24.4 Docker verification

Run/restart stack:

```powershell
docker compose up -d --build ai-agent-service frontend api-gateway postgres
```

Then check:

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8088/actuator/health
Invoke-WebRequest -UseBasicParsing http://localhost:3000/index.html
```

Manual UI verification:

1. Open `http://localhost:3000`.
2. Log in.
3. Open AI Agent settings.
4. Add OpenAI-compatible or DeepSeek provider.
5. Add model config.
6. Test connection.
7. Set default.
8. Open AI Agent page.
9. Select provider/model.
10. Create plan.
11. Confirm plan shows selected model metadata.

## 25. Acceptance Checklist

The task is done only when all checked:

1. `ai-agent-service` has persistent provider/model config.
2. API keys are encrypted at rest.
3. Provider CRUD API works.
4. Model CRUD API works.
5. Provider test API works.
6. OpenAI/GPT can be configured from frontend.
7. DeepSeek can be configured from frontend.
8. OpenAI-compatible endpoint can be configured from frontend.
9. AI Agent page can select provider/model.
10. `PlanRequest` sends selected provider/model IDs.
11. Backend uses selected/default provider/model.
12. Backend falls back to rules when no model provider is configured.
13. Raw API key never appears in API responses.
14. Frontend build passes.
15. Backend ai-agent-service build/tests pass.
16. Docker stack starts.

## 26. Important Constraints

1. Do not remove current rules fallback.
2. Do not break current OpenAI env-based behavior until DB config is working.
3. Do not implement AWS/Azure/Vertex.
4. Do not implement OAuth in MVP.
5. Do not expose raw API keys to frontend.
6. Do not store plaintext API keys.
7. Do not trust LLM output. Always sanitize.
8. Do not allow LLM to execute actions directly.
9. Mutating actions still require confirmation.
10. Keep changes scoped to AI Agent and necessary frontend routing/styles.

