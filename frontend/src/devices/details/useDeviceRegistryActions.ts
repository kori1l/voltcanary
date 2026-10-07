import { useState } from "react";
import {
  AnalyticsConfig,
  createDataSource,
  createDataSourceToken,
  createSensor,
  DataSource,
  deleteSensor,
  disableDataSource,
  exportDataSourcesCsv,
  getAnalyticsConfig,
  importDataSourcesCsv,
  listDataSources,
  listSensors,
  revokeDataSourceToken,
  Sensor,
  updateAnalyticsConfig,
} from "../devicesApi";
import { downloadText, parseJsonObject } from "./deviceDetailsFormatting";
import { useActionConfirmation } from "../../components/useActionConfirmation";
import { inspectImportCsv } from "../CsvImportPreview";

export function useDeviceRegistryActions({
  deviceId,
  onSourceTokenRotated,
}: {
  deviceId: string | undefined;
  onSourceTokenRotated: (dataSourceId: string, token: string) => Promise<void>;
}) {
  const [dataSources, setDataSources] = useState<DataSource[]>([]);
  const [sensors, setSensors] = useState<Sensor[]>([]);
  const [analyticsConfig, setAnalyticsConfig] = useState<AnalyticsConfig | null>(null);
  const [analyticsConfigText, setAnalyticsConfigText] = useState("{\n  \"baselineWindowDays\": 14\n}");
  const [dataSourceExternalId, setDataSourceExternalId] = useState("");
  const [dataSourceType, setDataSourceType] = useState("SMART_PLUG");
  const [dataSourceProtocol, setDataSourceProtocol] = useState("REST");
  const [sourceCsvText, setSourceCsvText] = useState("externalId,sourceType,protocol\n");
  const [sourceCsvMessage, setSourceCsvMessage] = useState<string | null>(null);
  const [lastSourceToken, setLastSourceToken] = useState<string | null>(null);
  const [sensorName, setSensorName] = useState("");
  const [sensorType, setSensorType] = useState("");
  const [sensorUnit, setSensorUnit] = useState("");
  const [sensorMetricMapping, setSensorMetricMapping] = useState("");
  const [sensorMetadataText, setSensorMetadataText] = useState("{}");
  const [isRegistryBusy, setIsRegistryBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const confirmation = useActionConfirmation();
  const [registryLoaded, setRegistryLoaded] = useState(false);

  async function loadRegistry(currentDeviceId = deviceId) {
    if (!currentDeviceId) {
      return;
    }
    const [sourcesResponse, sensorsResponse, analyticsConfigResponse] = await Promise.allSettled([
      listDataSources(currentDeviceId),
      listSensors(currentDeviceId),
      getAnalyticsConfig(currentDeviceId),
    ]);
    if (sourcesResponse.status === "fulfilled") setDataSources(sourcesResponse.value);
    if (sensorsResponse.status === "fulfilled") setSensors(sensorsResponse.value);
    if (analyticsConfigResponse.status === "fulfilled") {
      setAnalyticsConfig(analyticsConfigResponse.value);
      setAnalyticsConfigText(JSON.stringify(analyticsConfigResponse.value.config ?? {}, null, 2));
    }
    const failures = [sourcesResponse, sensorsResponse, analyticsConfigResponse].flatMap((result, index) => result.status === "rejected" ? [["Sources", "Sensors", "Analytics configuration"][index]] : []);
    setRegistryLoaded(failures.length === 0);
    if (failures.length) setError(`${failures.join(", ")} unavailable. Retained records may be stale.`);
  }

  async function addDataSource() {
    if (!deviceId) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      await createDataSource(deviceId, {
        externalId: dataSourceExternalId,
        sourceType: dataSourceType,
        protocol: dataSourceProtocol,
      });
      setDataSourceExternalId("");
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to create data source");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function rotateSourceToken(dataSourceId: string) {
    if (!deviceId) return;
    if (await confirmation.confirm({ title: "Rotate source token", object: dataSources.find((source) => source.id === dataSourceId)?.externalId ?? dataSourceId, impact: "Replaces the active credential. External integrations using the previous token must be updated.", dependencies: "The attached simulator configuration is updated with the new token when available." }) === null) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      const response = await createDataSourceToken(deviceId, dataSourceId);
      setLastSourceToken(response.token);
      await onSourceTokenRotated(dataSourceId, response.token);
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to rotate source token");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function revokeSourceToken(dataSourceId: string) {
    if (!deviceId) return;
    if (await confirmation.confirm({ title: "Revoke source token", object: dataSources.find((source) => source.id === dataSourceId)?.externalId ?? dataSourceId, impact: "Invalidates the active credential. Senders using it can no longer authenticate telemetry." }) === null) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      await revokeDataSourceToken(deviceId, dataSourceId);
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to revoke source token");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function disableSource(dataSourceId: string) {
    if (!deviceId) return;
    if (await confirmation.confirm({ title: "Disable source", object: dataSources.find((source) => source.id === dataSourceId)?.externalId ?? dataSourceId, impact: "Disables ingestion from this source. Telemetry gaps can affect alerts and analytics." }) === null) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      await disableDataSource(deviceId, dataSourceId);
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to disable data source");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function importSourcesCsv() {
    if (!deviceId) return;
    if (!inspectImportCsv(sourceCsvText, ["externalId", "sourceType", "protocol"]).valid || isRegistryBusy) return;
    setIsRegistryBusy(true);
    setSourceCsvMessage(null);
    setError(null);
    try {
      const response = await importDataSourcesCsv(deviceId, sourceCsvText);
      setSourceCsvMessage(
        response.errors.length > 0
          ? `Imported ${response.importedCount}; ${response.errors.join("; ")}`
          : `Imported ${response.importedCount} data sources`,
      );
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to import data sources");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function exportSourcesCsv() {
    if (!deviceId) return;
    setError(null);
    try {
      downloadText(await exportDataSourcesCsv(deviceId), `device-${deviceId}-sources.csv`, "text/csv");
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to export data sources");
    }
  }

  async function addSensor() {
    if (!deviceId) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      await createSensor(deviceId, {
        name: sensorName,
        type: sensorType,
        unit: sensorUnit || null,
        metricMapping: sensorMetricMapping || null,
        metadata: parseJsonObject(sensorMetadataText),
      });
      setSensorName("");
      setSensorType("");
      setSensorUnit("");
      setSensorMetricMapping("");
      setSensorMetadataText("{}");
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to create sensor");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function removeSensor(sensorId: string) {
    if (!deviceId) return;
    if (await confirmation.confirm({ title: "Delete sensor", object: sensors.find((sensor) => sensor.id === sensorId)?.name ?? sensorId, impact: "Removes this physical sensor mapping. Existing telemetry history is retained." }) === null) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      await deleteSensor(deviceId, sensorId);
      await loadRegistry(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to delete sensor");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  async function saveAnalyticsConfig() {
    if (!deviceId || !analyticsConfig) return;
    setIsRegistryBusy(true);
    setError(null);
    try {
      const config = parseJsonObject(analyticsConfigText) ?? {};
      if (config.baselineWindowDays !== undefined && (!Number.isInteger(config.baselineWindowDays) || Number(config.baselineWindowDays) < 1)) throw new Error("Baseline window days must be a positive integer.");
      const saved = await updateAnalyticsConfig(deviceId, {
        enabled: analyticsConfig.enabled,
        config,
      });
      setAnalyticsConfig(saved);
      setAnalyticsConfigText(JSON.stringify(saved.config ?? {}, null, 2));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to save analytics config");
    } finally {
      setIsRegistryBusy(false);
    }
  }

  return {
    confirmationDialog: confirmation.dialog,
    registryLoaded,
    dataSources,
    sensors,
    analyticsConfig,
    setAnalyticsConfig,
    analyticsConfigText,
    setAnalyticsConfigText,
    dataSourceExternalId,
    setDataSourceExternalId,
    dataSourceType,
    setDataSourceType,
    dataSourceProtocol,
    setDataSourceProtocol,
    sourceCsvText,
    setSourceCsvText,
    sourceCsvMessage,
    lastSourceToken,
    setLastSourceToken,
    sensorName,
    setSensorName,
    sensorType,
    setSensorType,
    sensorUnit,
    setSensorUnit,
    sensorMetricMapping,
    setSensorMetricMapping,
    sensorMetadataText,
    setSensorMetadataText,
    isRegistryBusy,
    error,
    setError,
    loadRegistry,
    addDataSource,
    rotateSourceToken,
    revokeSourceToken,
    disableSource,
    importSourcesCsv,
    exportSourcesCsv,
    addSensor,
    removeSensor,
    saveAnalyticsConfig,
  };
}
