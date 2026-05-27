import { FormEvent, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  createModelConfig,
  createModelProvider,
  deleteModelConfig,
  deleteModelProvider,
  listModelProviderCapabilities,
  listModelProviders,
  ModelProvider,
  ModelProviderCapability,
  rotateModelProviderSecret,
  setDefaultModelConfig,
  setDefaultModelProvider,
  testModelProvider,
} from "../aiAgent/aiAgentApi";

type ProviderForm = {
  name: string;
  providerType: string;
  baseUrl: string;
  apiKey: string;
  modelId: string;
  modelDisplayName: string;
  temperature: string;
  maxOutputTokens: string;
  timeoutMs: string;
  supportsJson: boolean;
  supportsTools: boolean;
  makeDefault: boolean;
};

type ModelForm = {
  displayName: string;
  modelId: string;
  temperature: string;
  maxOutputTokens: string;
  timeoutMs: string;
  supportsJson: boolean;
  supportsTools: boolean;
};

const EMPTY_PROVIDER_FORM: ProviderForm = {
  name: "",
  providerType: "OPENAI",
  baseUrl: "",
  apiKey: "",
  modelId: "gpt-5.4-mini",
  modelDisplayName: "GPT-5.4 Mini",
  temperature: "0.2",
  maxOutputTokens: "1800",
  timeoutMs: "30000",
  supportsJson: true,
  supportsTools: true,
  makeDefault: true,
};

const EMPTY_MODEL_FORM: ModelForm = {
  displayName: "",
  modelId: "",
  temperature: "0.2",
  maxOutputTokens: "1800",
  timeoutMs: "30000",
  supportsJson: true,
  supportsTools: false,
};

export function AiAgentSettingsPage() {
  const [capabilities, setCapabilities] = useState<ModelProviderCapability[]>([]);
  const [providers, setProviders] = useState<ModelProvider[]>([]);
  const [selectedProviderId, setSelectedProviderId] = useState("");
  const [providerForm, setProviderForm] = useState<ProviderForm>(EMPTY_PROVIDER_FORM);
  const [modelForm, setModelForm] = useState<ModelForm>(EMPTY_MODEL_FORM);
  const [rotateKey, setRotateKey] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isBusy, setIsBusy] = useState(false);

  const selectedCapability = capabilities.find((item) => item.providerType === providerForm.providerType);
  const selectedProvider = providers.find((provider) => provider.id === selectedProviderId) ?? providers[0] ?? null;

  const suggestedModels = useMemo(() => selectedCapability?.suggestedModels ?? [], [selectedCapability]);

  useEffect(() => {
    void load();
  }, []);

  useEffect(() => {
    if (!selectedProviderId && providers[0]) {
      setSelectedProviderId(providers[0].id);
    }
  }, [providers, selectedProviderId]);

  async function load() {
    setError(null);
    try {
      const [capabilityResponse, providerResponse] = await Promise.all([
        listModelProviderCapabilities(),
        listModelProviders().catch(() => []),
      ]);
      setCapabilities(capabilityResponse);
      setProviders(providerResponse);
      if (capabilityResponse[0]) {
        applyCapability(capabilityResponse[0], EMPTY_PROVIDER_FORM);
      }
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load model settings");
    }
  }

  function applyCapability(capability: ModelProviderCapability, base: ProviderForm = providerForm) {
    const firstModel = capability.suggestedModels[0];
    setProviderForm({
      ...base,
      providerType: capability.providerType,
      baseUrl: capability.defaultBaseUrl,
      modelId: firstModel?.modelId ?? base.modelId,
      modelDisplayName: firstModel?.displayName ?? firstModel?.modelId ?? base.modelDisplayName,
      supportsJson: capability.supportsJson,
      supportsTools: capability.supportsTools,
    });
  }

  async function submitProvider(event: FormEvent) {
    event.preventDefault();
    setIsBusy(true);
    setError(null);
    setMessage(null);
    try {
      const created = await createModelProvider({
        name: providerForm.name,
        providerType: providerForm.providerType,
        baseUrl: providerForm.baseUrl,
        apiKey: providerForm.apiKey,
        modelId: providerForm.modelId,
        modelDisplayName: providerForm.modelDisplayName || providerForm.modelId,
        temperature: numberOrUndefined(providerForm.temperature),
        maxOutputTokens: integerOrUndefined(providerForm.maxOutputTokens),
        timeoutMs: integerOrUndefined(providerForm.timeoutMs),
        supportsJson: providerForm.supportsJson,
        supportsTools: providerForm.supportsTools,
        makeDefault: providerForm.makeDefault,
      });
      setMessage("Provider saved");
      setProviderForm({ ...providerForm, apiKey: "" });
      await load();
      setSelectedProviderId(created.id);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to save provider");
    } finally {
      setIsBusy(false);
    }
  }

  async function submitModel(event: FormEvent) {
    event.preventDefault();
    if (!selectedProvider) return;
    setIsBusy(true);
    setError(null);
    setMessage(null);
    try {
      await createModelConfig(selectedProvider.id, {
        displayName: modelForm.displayName || modelForm.modelId,
        modelId: modelForm.modelId,
        enabled: true,
        makeDefault: selectedProvider.models.length === 0,
        temperature: numberOrUndefined(modelForm.temperature),
        maxOutputTokens: integerOrUndefined(modelForm.maxOutputTokens),
        timeoutMs: integerOrUndefined(modelForm.timeoutMs),
        supportsJson: modelForm.supportsJson,
        supportsTools: modelForm.supportsTools,
      });
      setModelForm(EMPTY_MODEL_FORM);
      setMessage("Model added");
      await load();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to add model");
    } finally {
      setIsBusy(false);
    }
  }

  async function providerAction(action: () => Promise<unknown>, success: string) {
    setIsBusy(true);
    setError(null);
    setMessage(null);
    try {
      await action();
      setMessage(success);
      await load();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Action failed");
    } finally {
      setIsBusy(false);
    }
  }

  return (
    <>
      <header className="page-header page-header-row">
        <div>
          <h1>AI Model Settings</h1>
          <p>Configure direct model providers for the AI Agent.</p>
        </div>
        <Link className="ghost-button button-link" to="/ai-agent">
          Back to agent
        </Link>
      </header>

      {error ? <div className="error-message">{error}</div> : null}
      {message ? <div className="success-message">{message}</div> : null}

      <section className="agent-settings-layout">
        <aside className="panel agent-provider-list">
          <div className="section-header">
            <div>
              <h2>Providers</h2>
              <p>{providers.length} configured</p>
            </div>
          </div>
          {providers.length === 0 ? (
            <div className="empty-state">
              <strong>No model providers</strong>
              <span>Add OpenAI, DeepSeek, or another OpenAI-compatible endpoint.</span>
            </div>
          ) : null}
          {providers.map((provider) => (
            <button
              className={`agent-provider-card${selectedProvider?.id === provider.id ? " agent-provider-card-active" : ""}`}
              type="button"
              key={provider.id}
              onClick={() => setSelectedProviderId(provider.id)}
            >
              <span>{provider.providerType}</span>
              <strong>{provider.name}</strong>
              <em>{provider.models.find((model) => model.isDefault)?.modelId ?? "No default model"}</em>
              {provider.isDefault ? <small>Default</small> : null}
            </button>
          ))}
        </aside>

        <div className="agent-settings-main">
          <form className="panel agent-provider-form" onSubmit={submitProvider}>
            <div className="section-header">
              <div>
                <h2>Add provider</h2>
                <p>API keys are stored encrypted on the backend.</p>
              </div>
            </div>
            <div className="form-grid-three">
              <label>
                Provider
                <select
                  value={providerForm.providerType}
                  onChange={(event) => {
                    const capability = capabilities.find((item) => item.providerType === event.target.value);
                    if (capability) applyCapability(capability);
                  }}
                >
                  {capabilities.map((capability) => (
                    <option value={capability.providerType} key={capability.providerType}>
                      {capability.displayName}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Name
                <input value={providerForm.name} onChange={(event) => setProviderForm({ ...providerForm, name: event.target.value })} required />
              </label>
              <label>
                Base URL
                <input value={providerForm.baseUrl} onChange={(event) => setProviderForm({ ...providerForm, baseUrl: event.target.value })} />
              </label>
            </div>
            <div className="form-grid-three">
              <label>
                API key
                <input
                  value={providerForm.apiKey}
                  onChange={(event) => setProviderForm({ ...providerForm, apiKey: event.target.value })}
                  type="password"
                  autoComplete="off"
                  required={selectedCapability?.apiKeyRequired}
                />
              </label>
              <label>
                Default model
                <input list="ai-provider-models" value={providerForm.modelId} onChange={(event) => setProviderForm({ ...providerForm, modelId: event.target.value })} required />
                <datalist id="ai-provider-models">
                  {suggestedModels.map((model) => <option value={model.modelId} key={model.modelId} />)}
                </datalist>
              </label>
              <label>
                Model label
                <input value={providerForm.modelDisplayName} onChange={(event) => setProviderForm({ ...providerForm, modelDisplayName: event.target.value })} />
              </label>
            </div>
            <div className="form-grid-three">
              <label>
                Temperature
                <input value={providerForm.temperature} onChange={(event) => setProviderForm({ ...providerForm, temperature: event.target.value })} type="number" min="0" max="2" step="0.1" />
              </label>
              <label>
                Max tokens
                <input value={providerForm.maxOutputTokens} onChange={(event) => setProviderForm({ ...providerForm, maxOutputTokens: event.target.value })} type="number" min="128" max="32000" />
              </label>
              <label>
                Timeout ms
                <input value={providerForm.timeoutMs} onChange={(event) => setProviderForm({ ...providerForm, timeoutMs: event.target.value })} type="number" min="1000" max="120000" />
              </label>
            </div>
            <div className="action-row">
              <label className="inline-toggle">
                <input type="checkbox" checked={providerForm.supportsJson} onChange={(event) => setProviderForm({ ...providerForm, supportsJson: event.target.checked })} />
                JSON mode
              </label>
              <label className="inline-toggle">
                <input type="checkbox" checked={providerForm.supportsTools} onChange={(event) => setProviderForm({ ...providerForm, supportsTools: event.target.checked })} />
                Tool support
              </label>
              <label className="inline-toggle">
                <input type="checkbox" checked={providerForm.makeDefault} onChange={(event) => setProviderForm({ ...providerForm, makeDefault: event.target.checked })} />
                Set default
              </label>
              <button className="primary-button" type="submit" disabled={isBusy}>
                Save provider
              </button>
            </div>
          </form>

          {selectedProvider ? (
            <section className="panel agent-provider-detail">
              <div className="section-header">
                <div>
                  <h2>{selectedProvider.name}</h2>
                  <p>
                    {selectedProvider.providerType} - {selectedProvider.baseUrl}
                  </p>
                </div>
                <div className="action-row">
                  <button className="ghost-button" type="button" onClick={() => void providerAction(() => testModelProvider(selectedProvider.id), "Provider test completed")} disabled={isBusy}>
                    Test
                  </button>
                  <button className="ghost-button" type="button" onClick={() => void providerAction(() => setDefaultModelProvider(selectedProvider.id), "Default provider updated")} disabled={isBusy || selectedProvider.isDefault}>
                    Set default
                  </button>
                  <button className="danger-button" type="button" onClick={() => void providerAction(() => deleteModelProvider(selectedProvider.id), "Provider deleted")} disabled={isBusy}>
                    Delete
                  </button>
                </div>
              </div>
              <div className="agent-test-result">
                <span>Key: {selectedProvider.maskedSecretHint ?? "not stored"}</span>
                <span>Last test: {selectedProvider.lastTestStatus ?? "not tested"}</span>
                <span>{selectedProvider.lastTestMessage ?? "No test result yet"}</span>
              </div>
              <div className="action-row agent-secret-rotation">
                <input value={rotateKey} onChange={(event) => setRotateKey(event.target.value)} type="password" placeholder="New API key" />
                <button
                  className="ghost-button"
                  type="button"
                  onClick={() => void providerAction(() => rotateModelProviderSecret(selectedProvider.id, rotateKey), "API key rotated")}
                  disabled={isBusy || !rotateKey.trim()}
                >
                  Rotate key
                </button>
              </div>

              <div className="agent-model-table">
                <div className="agent-model-row agent-model-row-head">
                  <span>Default</span>
                  <span>Model</span>
                  <span>Settings</span>
                  <span>Actions</span>
                </div>
                {selectedProvider.models.map((model) => (
                  <article className="agent-model-row" key={model.id}>
                    <span>{model.isDefault ? "Yes" : "No"}</span>
                    <div>
                      <strong>{model.displayName}</strong>
                      <small>{model.modelId}</small>
                    </div>
                    <span>{model.maxOutputTokens ?? 1800} tokens - {model.timeoutMs ?? 30000} ms</span>
                    <div className="action-row">
                      <button className="ghost-button" type="button" onClick={() => void providerAction(() => setDefaultModelConfig(selectedProvider.id, model.id), "Default model updated")} disabled={model.isDefault || isBusy}>
                        Default
                      </button>
                      <button className="danger-button" type="button" onClick={() => void providerAction(() => deleteModelConfig(selectedProvider.id, model.id), "Model deleted")} disabled={isBusy}>
                        Delete
                      </button>
                    </div>
                  </article>
                ))}
              </div>

              <form className="agent-model-form" onSubmit={submitModel}>
                <h3>Add model</h3>
                <div className="form-grid-three">
                  <label>
                    Model ID
                    <input value={modelForm.modelId} onChange={(event) => setModelForm({ ...modelForm, modelId: event.target.value })} required />
                  </label>
                  <label>
                    Label
                    <input value={modelForm.displayName} onChange={(event) => setModelForm({ ...modelForm, displayName: event.target.value })} />
                  </label>
                  <label>
                    Max tokens
                    <input value={modelForm.maxOutputTokens} onChange={(event) => setModelForm({ ...modelForm, maxOutputTokens: event.target.value })} type="number" min="128" max="32000" />
                  </label>
                </div>
                <button className="primary-button" type="submit" disabled={isBusy || !modelForm.modelId.trim()}>
                  Add model
                </button>
              </form>
            </section>
          ) : null}
        </div>
      </section>
    </>
  );
}

function numberOrUndefined(value: string) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function integerOrUndefined(value: string) {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : undefined;
}
