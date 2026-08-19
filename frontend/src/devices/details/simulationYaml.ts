export type SimulationSettings = {
  name: string;
  sourceId: string;
  interval: string;
  duration: string;
  speed: string;
  voltageV: string;
  rssi: string;
  idlePowerW: string;
  activePowerW: string;
  activeNoiseW: string;
  onMin: string;
  onMax: string;
  offMin: string;
  offMax: string;
  powerSpikeEnabled: boolean;
  powerSpikeAfter: string;
  powerSpikeDuration: string;
  powerSpikeWatts: string;
  offlineEnabled: boolean;
  offlineAfter: string;
  offlineDuration: string;
  growthEnabled: boolean;
  growthAfter: string;
  growthPercent: string;
  growthRamp: string;
};

export function readSimulationSettings(yaml: string): SimulationSettings {
  return {
    name: readYamlString(yaml, /name:[ \t]*"?([^"\n]*)"?/),
    sourceId: readYamlString(yaml, /sourceId:[ \t]*"?([^"\n]*)"?/),
    interval: readYamlString(yaml, /interval:[ \t]*([^\n]*)/),
    duration: readYamlString(yaml, /duration:[ \t]*([^\n]*)/),
    speed: readYamlString(yaml, /speed:[ \t]*([^\n]*)/),
    voltageV: readYamlString(yaml, /voltageV:[ \t]*\n[ \t]+type:[ \t]+constant[ \t]*\n[ \t]+value:[ \t]*([^\n]*)/),
    rssi: readYamlString(yaml, /rssi:[ \t]*\n[ \t]+type:[ \t]+constant[ \t]*\n[ \t]+value:[ \t]*([^\n]*)/),
    idlePowerW: readYamlString(yaml, /offValue:[ \t]*\n[ \t]+mean:[ \t]*([^\n]*)/),
    activePowerW: readYamlString(yaml, /onValue:[ \t]*\n[ \t]+mean:[ \t]*([^\n]*)/),
    activeNoiseW: readYamlString(yaml, /onValue:[ \t]*\n[ \t]+mean:[ \t]*[^\n]*\n[ \t]+noise:[ \t]*([^\n]*)/),
    onMin: readYamlString(yaml, /onDuration:[ \t]*\n[ \t]+min:[ \t]*([^\n]*)/),
    onMax: readYamlString(yaml, /onDuration:[ \t]*\n[ \t]+min:[ \t]*[^\n]*\n[ \t]+max:[ \t]*([^\n]*)/),
    offMin: readYamlString(yaml, /offDuration:[ \t]*\n[ \t]+min:[ \t]*([^\n]*)/),
    offMax: readYamlString(yaml, /offDuration:[ \t]*\n[ \t]+min:[ \t]*[^\n]*\n[ \t]+max:[ \t]*([^\n]*)/),
    powerSpikeEnabled: readAnomalyBoolean(yaml, "POWER_SPIKE", "enabled"),
    powerSpikeAfter: readAnomalyString(yaml, "POWER_SPIKE", "startAfter"),
    powerSpikeDuration: readAnomalyString(yaml, "POWER_SPIKE", "duration"),
    powerSpikeWatts: readAnomalyString(yaml, "POWER_SPIKE", "powerW"),
    offlineEnabled: readAnomalyBoolean(yaml, "OFFLINE", "enabled"),
    offlineAfter: readAnomalyString(yaml, "OFFLINE", "startAfter"),
    offlineDuration: readAnomalyString(yaml, "OFFLINE", "duration"),
    growthEnabled: readAnomalyBoolean(yaml, "CONSUMPTION_GROWTH", "enabled"),
    growthAfter: readAnomalyString(yaml, "CONSUMPTION_GROWTH", "startAfter"),
    growthPercent: readAnomalyString(yaml, "CONSUMPTION_GROWTH", "growthPercent"),
    growthRamp: readAnomalyString(yaml, "CONSUMPTION_GROWTH", "rampDuration"),
  };
}

export function updateSimulationYaml(yaml: string, field: keyof SimulationSettings | "profile" | "protocol", value: string | boolean) {
  const textValue = typeof value === "boolean" ? String(value) : value;
  switch (field) {
    case "name":
      return replaceYaml(yaml, /(name:[ \t]*)"?([^"\n]*)"?/, `$1${textValue}`);
    case "profile":
      return replaceYaml(yaml, /(profile:[ \t]*)[A-Z_]*/, `$1${textValue}`);
    case "protocol":
      return replaceYaml(yaml, /(protocol:[ \t]*)[A-Z_]*/, `$1${textValue}`);
    case "sourceId":
      return replaceYaml(yaml, /(sourceId:[ \t]*)"?([^"\n]*)"?/, `$1"${textValue}"`);
    case "interval":
      return replaceYaml(yaml, /(interval:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "duration":
      return replaceYaml(yaml, /(duration:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "speed":
      return replaceYaml(yaml, /(speed:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "voltageV":
      return replaceYaml(yaml, /(voltageV:[ \t]*\n[ \t]+type:[ \t]+constant[ \t]*\n[ \t]+value:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "rssi":
      return replaceYaml(yaml, /(rssi:[ \t]*\n[ \t]+type:[ \t]+constant[ \t]*\n[ \t]+value:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "idlePowerW":
      return replaceYaml(yaml, /(offValue:[ \t]*\n[ \t]+mean:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "activePowerW":
      return replaceYaml(yaml, /(onValue:[ \t]*\n[ \t]+mean:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "activeNoiseW":
      return replaceYaml(yaml, /(onValue:[ \t]*\n[ \t]+mean:[ \t]*[^\n]*\n[ \t]+noise:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "onMin":
      return replaceYaml(yaml, /(onDuration:[ \t]*\n[ \t]+min:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "onMax":
      return replaceYaml(yaml, /(onDuration:[ \t]*\n[ \t]+min:[ \t]*[^\n]*\n[ \t]+max:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "offMin":
      return replaceYaml(yaml, /(offDuration:[ \t]*\n[ \t]+min:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "offMax":
      return replaceYaml(yaml, /(offDuration:[ \t]*\n[ \t]+min:[ \t]*[^\n]*\n[ \t]+max:[ \t]*)[^\n]*/, `$1${textValue}`);
    case "powerSpikeEnabled":
      return updateAnomalyYaml(yaml, "POWER_SPIKE", "enabled", textValue);
    case "powerSpikeAfter":
      return updateAnomalyYaml(yaml, "POWER_SPIKE", "startAfter", textValue);
    case "powerSpikeDuration":
      return updateAnomalyYaml(yaml, "POWER_SPIKE", "duration", textValue);
    case "powerSpikeWatts":
      return updateAnomalyYaml(yaml, "POWER_SPIKE", "powerW", textValue);
    case "offlineEnabled":
      return updateAnomalyYaml(yaml, "OFFLINE", "enabled", textValue);
    case "offlineAfter":
      return updateAnomalyYaml(yaml, "OFFLINE", "startAfter", textValue);
    case "offlineDuration":
      return updateAnomalyYaml(yaml, "OFFLINE", "duration", textValue);
    case "growthEnabled":
      return updateAnomalyYaml(yaml, "CONSUMPTION_GROWTH", "enabled", textValue);
    case "growthAfter":
      return updateAnomalyYaml(yaml, "CONSUMPTION_GROWTH", "startAfter", textValue);
    case "growthPercent":
      return updateAnomalyYaml(yaml, "CONSUMPTION_GROWTH", "growthPercent", textValue);
    case "growthRamp":
      return updateAnomalyYaml(yaml, "CONSUMPTION_GROWTH", "rampDuration", textValue);
    default:
      return yaml;
  }
}

function readYamlString(yaml: string, pattern: RegExp) {
  return yaml.match(pattern)?.[1]?.trim() ?? "";
}

function readAnomalyString(yaml: string, type: string, field: string) {
  const block = anomalyBlock(yaml, type);
  return block.match(new RegExp(`${field}:[ \\t]*([^\\n]*)`))?.[1]?.trim() ?? "";
}

function readAnomalyBoolean(yaml: string, type: string, field: string) {
  return readAnomalyString(yaml, type, field).toLowerCase() === "true";
}

function replaceYaml(yaml: string, pattern: RegExp, replacement: string) {
  if (!pattern.test(yaml)) {
    return yaml;
  }
  if (replacement.startsWith("$1")) {
    const value = replacement.slice(2);
    return yaml.replace(pattern, (_match, prefix: string) => `${prefix}${value}`);
  }
  return yaml.replace(pattern, replacement);
}

function anomalyBlock(yaml: string, type: string) {
  const match = yaml.match(new RegExp(`  - type: ${type}[\\s\\S]*?(?=\\n  - type:|\\n?$)`));
  return match?.[0] ?? "";
}

function updateAnomalyYaml(yaml: string, type: string, field: string, value: string) {
  const block = anomalyBlock(yaml, type);
  if (!block) {
    return yaml;
  }
  const updatedBlock = new RegExp(`(\\n[ \\t]+${field}:[ \\t]*)[^\\n]*`).test(block)
    ? block.replace(new RegExp(`(\\n[ \\t]+${field}:[ \\t]*)[^\\n]*`), (_match, prefix: string) => `${prefix}${value}`)
    : block.replace(new RegExp(`(  - type: ${type})`), `$1\n    ${field}: ${value}`);
  return yaml.replace(block, updatedBlock);
}
