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

  async function loadRegistry(currentDeviceId = deviceId) {
    if (!currentDeviceId) {
      return;
    }
    const [sourcesResponse, sensorsResponse, analyticsConfigResponse] = await Promise.all([
      listDataSources(currentDeviceId).catch(() => []),
      listSensors(currentDeviceId).catch(() => []),
      getAnalyticsConfig(currentDeviceId).catch(() => null),
    ]);
    setDataSources(sourcesResponse);
    setSensors(sensorsResponse);
    setAnalyticsConfig(analyticsConfigResponse);
    if (analyticsConfigResponse) {
      setAnalyticsConfigText(JSON.stringify(analyticsConfigResponse.config ?? {}, null, 2));
    }
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
      const saved = await updateAnalyticsConfig(deviceId, {
        enabled: analyticsConfig.enabled,
        config: parseJsonObject(analyticsConfigText) ?? {},
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
