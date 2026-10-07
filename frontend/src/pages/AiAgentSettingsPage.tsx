import { ModelForm, ProviderModelForm } from "../aiAgent/ProviderModelForm";
import { useActionConfirmation } from "../components/useActionConfirmation";
import { ProviderConnectionForm, ProviderForm } from "../aiAgent/ProviderConnectionForm";
import { ProviderSecretRotation } from "../aiAgent/ProviderSecretRotation";
import { FormEvent, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { createModelConfig, createModelProvider, deleteModelConfig, deleteModelProvider, listModelProviderCapabilities, listModelProviders, ModelProvider, ModelProviderCapability, setDefaultModelConfig, setDefaultModelProvider, testModelProvider } from "../aiAgent/aiAgentApi";

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
  const confirmation = useActionConfirmation();
  const [loaded, setLoaded] = useState(false);
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
        listModelProviders(),
      ]);
      setCapabilities(capabilityResponse);
      setProviders(providerResponse);
      setLoaded(true);
      if (capabilityResponse[0] && capabilities.length === 0) {
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
      apiKey: "",
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
    if (isBusy || !selectedCapability) return;
    setIsBusy(true);
    setError(null);
    setMessage(null);
    try {
      const created = await createModelProvider({
        name: providerForm.name,
        providerType: providerForm.providerType,
        baseUrl: providerForm.baseUrl,
        apiKey: selectedCapability.apiKeyRequired ? providerForm.apiKey : undefined,
        modelId: providerForm.modelId,
        modelDisplayName: providerForm.modelDisplayName || providerForm.modelId,
        temperature: numberOrUndefined(providerForm.temperature),
        maxOutputTokens: integerOrUndefined(providerForm.maxOutputTokens),
        timeoutMs: integerOrUndefined(providerForm.timeoutMs),
        supportsJson: providerForm.supportsJson && selectedCapability.supportsJson,
        supportsTools: providerForm.supportsTools && selectedCapability.supportsTools,
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
    if (!selectedProvider || isBusy) return;
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
        supportsJson: modelForm.supportsJson && Boolean(capabilities.find((item) => item.providerType === selectedProvider.providerType)?.supportsJson),
        supportsTools: modelForm.supportsTools && Boolean(capabilities.find((item) => item.providerType === selectedProvider.providerType)?.supportsTools),
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

  async function providerAction(action: () => Promise<unknown>, success: string, review?: { object: string; impact: string }) {
    if (isBusy || (review && await confirmation.confirm({ title: success.replace(/ updated| deleted/, ""), ...review }) === null)) return;
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
      {confirmation.dialog}
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
              <p>{loaded ? `${providers.length} configured` : "Provider count unavailable"}</p>
            </div>
          </div>
          {loaded && providers.length === 0 ? (
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
          <ProviderConnectionForm draft={providerForm} capabilities={capabilities} onChange={(update) => setProviderForm((current) => ({ ...current, ...update }))} onCapability={applyCapability} onSubmit={submitProvider} busy={isBusy} />

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
                  <button className="danger-button" type="button" onClick={() => void providerAction(() => deleteModelProvider(selectedProvider.id), "Provider deleted", { object: selectedProvider.name, impact: "Remove this provider and its model configurations from future plan selection. Existing plan authorization remains enforced by the server." })} disabled={isBusy}>
                    Delete
                  </button>
                </div>
              </div>
              <div className="agent-test-result">
                <span>Key: {selectedProvider.authType === "NONE" ? "Not required" : selectedProvider.maskedSecretHint ? "Configured" : "Missing"}</span>
                <span>Last test: {selectedProvider.lastTestStatus ?? "not tested"}</span>
                <span>{selectedProvider.lastTestMessage ?? "No test result yet"}</span>
              </div>
              <ProviderSecretRotation key={selectedProvider.id} provider={selectedProvider} onChanged={load} />

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
                      <button className="danger-button" type="button" onClick={() => void providerAction(() => deleteModelConfig(selectedProvider.id, model.id), "Model deleted", { object: `${selectedProvider.name}: ${model.displayName}`, impact: "Remove this model configuration from future assistant requests." })} disabled={isBusy}>
                        Delete
                      </button>
                    </div>
                  </article>
                ))}
              </div>

              <ProviderModelForm draft={modelForm} capability={capabilities.find((item) => item.providerType === selectedProvider.providerType)} busy={isBusy} onChange={setModelForm} onSubmit={submitModel} />
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
