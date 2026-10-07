import http from "k6/http";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";
import { SharedArray } from "k6/data";
import { sleep } from "k6";
import exec from "k6/execution";

const runDirectory = __ENV.RUN_DIRECTORY;
if (!runDirectory) {
  throw new Error("RUN_DIRECTORY is required.");
}

const ingestionBaseUrl = (__ENV.INGESTION_BASE_URL || "http://localhost:8083").replace(/\/$/, "");
const telemetryBaseUrl = (__ENV.TELEMETRY_BASE_URL || "http://localhost:8084").replace(/\/$/, "");
const internalServiceToken = __ENV.INTERNAL_SERVICE_TOKEN || "dev-internal-token";
const sourceTokenPath = __ENV.SOURCE_TOKENS_PATH || `${runDirectory}/provisioning-secrets.local.json`;
const planPath = `${runDirectory}/generation-manifest.json`;
const plan = new SharedArray("capacity-plan-metadata", () => {
  const document = JSON.parse(open(planPath));
  delete document.readings;
  delete document.warmupReadings;
  return [document];
})[0];
const sourceTokenItems = new SharedArray("capacity-source-tokens", () => {
  const document = JSON.parse(open(sourceTokenPath));
  return document.sourceTokens || [];
});
const tokenBySource = new Map();
for (const item of sourceTokenItems) {
  if (item.externalId) {
    tokenBySource.set(item.externalId, item.token);
  }
  if (item.apiDataSourceId) {
    tokenBySource.set(item.apiDataSourceId, item.token);
  }
}
const arrival = plan.arrivalSchedule || {};
const warmupArrival = plan.warmupSchedule || {};
const runStart = new Date(__ENV.RUN_START || (plan.runEpoch && plan.runEpoch.iso) || Date.now());

const scheduledReadings = new Counter("capacity_scheduled_readings");
const startedReadings = new Counter("capacity_started_readings");
const transportAttempts = new Counter("capacity_transport_attempts");
const retryReadings = new Counter("capacity_retry_readings");
const acceptedReadings = new Counter("capacity_accepted_readings");
const duplicateReadings = new Counter("capacity_duplicate_readings");
const rejectedReadings = new Counter("capacity_rejected_readings");
const unknownReadings = new Counter("capacity_unknown_readings");
const droppedReadings = new Counter("capacity_dropped_readings");
const batchItems = new Counter("capacity_batch_items");
const batchLatency = new Trend("capacity_batch_latency", true);
const visibilityLatency = new Trend("capacity_visibility_latency", true);
const visibilityTimeouts = new Counter("capacity_visibility_timeouts");

function metricValue(sequence, metricName) {
  switch (metricName) {
    case "activePowerW":
      return Number((350 + ((sequence * 17) % 650) + ((sequence % 10) / 10)).toFixed(3));
    case "voltageV":
      return Number((220 + ((sequence * 7) % 20) + ((sequence % 5) / 10)).toFixed(3));
    case "currentA":
      return Number((1 + (((sequence * 11) % 900) / 100)).toFixed(3));
    case "energyWh":
      return Number((10000 + (sequence * 5)).toFixed(3));
    case "rssi":
      return Number((-70 + (sequence % 25)).toFixed(3));
    case "temperatureC":
      return Number((18 + (((sequence * 3) % 550) / 10)).toFixed(3));
    case "vibrationMmS":
      return Number((((sequence * 13) % 1200) / 100).toFixed(3));
    case "runtimeHours":
      return Number(((sequence * 5) / 60).toFixed(3));
    case "powerFactor":
      return Number((0.7 + (((sequence * 7) % 30) / 100)).toFixed(3));
    case "frequencyHz":
      return Number((49 + (((sequence * 5) % 250) / 100)).toFixed(3));
    default:
      throw new Error(`Unsupported metric in generation plan: ${metricName}`);
  }
}

function payloadFor(reading) {
  const metrics = {};
  for (const metricName of reading.metricNames || []) {
    metrics[metricName] = metricValue(Number(reading.sequence), metricName);
  }
  const timestamp = new Date(runStart.getTime() + (Number(reading.recordedAtOffsetSeconds) * 1000)).toISOString();
  return {
    deviceId: reading.deviceId,
    sourceId: reading.sourceId,
    timestamp,
    metrics,
  };
}

function batchesFor(readings, batchSize) {
  const batches = [];
  for (let index = 0; index < readings.length; index += batchSize) {
    batches.push(readings.slice(index, index + batchSize));
  }
  return batches;
}

const batches = new SharedArray("capacity-rest-batches", () => {
  const document = JSON.parse(open(planPath));
  return batchesFor(document.readings || [], Number(document.accounting.batchSize || 1));
});
const warmupBatches = new SharedArray("capacity-rest-warmup-batches", () => {
  const document = JSON.parse(open(planPath));
  return batchesFor(document.warmupReadings || [], Number(document.accounting.batchSize || 1));
});
const wallClockStartMillis = Number(__ENV.SCHEDULE_WALL_CLOCK_START_MS || Date.now());
const measurementWallClockStartMillis = wallClockStartMillis + (Number(warmupArrival.durationSeconds || 0) * 1000);
const maxRetries = Number(__ENV.MAX_RETRIES || arrival.maxRetries || 1);

function iterationBatch(sourceBatches) {
  const iteration = exec.scenario.iterationInTest;
  const batch = sourceBatches[iteration];
  if (!batch) {
    droppedReadings.add(Number(plan.accounting.batchSize || 1));
    return null;
  }
  return batch;
}

function waitForSchedule(batch, phase) {
  if (__ENV.DISABLE_SCHEDULE_WAIT === "1") {
    return;
  }
  const firstOffsetMillis = phase === "warmup"
    ? Math.min(...batch.map((reading) => Number(reading.scheduleOffsetSeconds || 0) * 1000))
    : Math.min(...batch.map((reading) => Number(reading.recordedAtOffsetSeconds) * 1000));
  const phaseStartMillis = phase === "warmup" ? wallClockStartMillis : measurementWallClockStartMillis;
  const elapsedMillis = Date.now() - phaseStartMillis;
  const delayMillis = firstOffsetMillis - elapsedMillis;
  if (delayMillis > 0) {
    sleep(delayMillis / 1000);
  }
}

function itemStatuses(body, batchLength) {
  const statuses = new Array(batchLength).fill("unknown");
  const items = Array.isArray(body && body.items) ? body.items : [];
  for (const item of items) {
    const index = Number(item.index);
    if (Number.isInteger(index) && index >= 0 && index < statuses.length) {
      statuses[index] = String(item.status || "unknown");
    }
  }
  return statuses;
}

function recordApplicationOutcome(batch, body) {
  const statuses = itemStatuses(body, batch.length);
  for (const status of statuses) {
    if (status === "accepted") {
      acceptedReadings.add(1);
    } else if (status === "duplicate") {
      duplicateReadings.add(1);
    } else if (status === "rejected") {
      rejectedReadings.add(1);
    } else {
      unknownReadings.add(1);
    }
  }
}

function emitOutcome(batch, attempt, body, status) {
  if (__ENV.CAPACITY_K6_LOG_OUTCOMES !== "1") {
    return;
  }
  console.log(JSON.stringify({
    type: "capacity-rest-batch",
    runId: plan.runId,
    workerIndex: plan.workerIndex,
    iteration: exec.scenario.iterationInTest,
    attempt,
    status,
    sequences: batch.map((reading) => Number(reading.sequence)),
    itemStatuses: status === "accepted" ? itemStatuses(body, batch.length) : [],
    responseCompletedAtUnixMs: Date.now(),
  }));
}

function shouldObserve(reading) {
  const scheduled = Number(plan.accounting.workerMaterializedReadings || 0);
  const percent = Number(__ENV.VISIBILITY_SAMPLE_PERCENT || 1);
  const maxSamples = Number(__ENV.VISIBILITY_MAX_SAMPLES || 100);
  const target = Math.max(1, Math.min(maxSamples, Math.ceil(scheduled * percent / 100)));
  const stride = Math.max(1, Math.ceil(scheduled / target));
  return Number(reading.sequence) % stride === 0;
}

function observeVisibility(reading, acceptedAtMillis) {
  if (!shouldObserve(reading)) {
    return;
  }
  const metricName = (reading.metricNames || [])[0];
  const expectedValue = metricValue(Number(reading.sequence), metricName);
  const recordedAtMillis = runStart.getTime() + (Number(reading.recordedAtOffsetSeconds) * 1000);
  const recordedAt = new Date(recordedAtMillis).toISOString();
  const from = new Date(recordedAtMillis - 2).toISOString();
  const to = new Date(recordedAtMillis + 2).toISOString();
  const timeoutMillis = Number(__ENV.VISIBILITY_TIMEOUT_MILLIS || 30000);
  const pollMillis = Number(__ENV.VISIBILITY_POLL_MILLIS || 250);
  const deadline = acceptedAtMillis + timeoutMillis;
  let observed = false;
  let lastError = null;
  while (Date.now() < deadline && !observed) {
    const url = `${telemetryBaseUrl}/telemetry/devices/${reading.deviceId}/readings/export?metric=${encodeURIComponent(metricName)}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&limit=1`;
    const response = http.get(url, {
      headers: {
        "X-Internal-Service-Token": internalServiceToken,
        "X-Correlation-Id": `capacity-visibility-${plan.runId}-${reading.sequence}`,
      },
      timeout: __ENV.VISIBILITY_REQUEST_TIMEOUT || "5s",
      tags: { capacity_phase: "visibility" },
    });
    if (response.status === 200) {
      try {
        const body = response.json();
        observed = (body.points || []).some((point) => Math.abs(new Date(point.timestamp).getTime() - recordedAtMillis) <= 2 && Math.abs(Number(point.value) - expectedValue) <= 0.000001);
      } catch (error) {
        lastError = String(error);
      }
    } else {
      lastError = `HTTP ${response.status}`;
    }
    if (!observed) {
      sleep(pollMillis / 1000);
    }
  }
  const latencyMillis = observed ? Date.now() - acceptedAtMillis : null;
  if (observed) {
    visibilityLatency.add(latencyMillis);
  } else {
    visibilityTimeouts.add(1);
  }
  console.log(JSON.stringify({
    type: "capacity-rest-visibility",
    runId: plan.runId,
    sequence: Number(reading.sequence),
    observed,
    latencyMillis,
    acceptedAtUnixMs: acceptedAtMillis,
    pollingIntervalMillis: pollMillis,
    timeoutMillis,
    error: lastError,
  }));
}

const scenarios = {
  capacity_rest: {
      executor: "constant-arrival-rate",
      exec: "measurement",
      startTime: `${Number(warmupArrival.durationSeconds || 0)}s`,
      rate: Number(__ENV.CAPACITY_BATCH_RATE || batches.length),
      timeUnit: __ENV.CAPACITY_TIME_UNIT || arrival.timeUnit || "1s",
      duration: __ENV.CAPACITY_DURATION || arrival.duration || "1s",
      preAllocatedVUs: Number(__ENV.CAPACITY_PRE_ALLOCATED_VUS || arrival.preAllocatedVUs || 1),
      maxVUs: Number(__ENV.CAPACITY_MAX_VUS || arrival.maxVUs || 1),
      gracefulStop: __ENV.CAPACITY_GRACEFUL_STOP || arrival.gracefulStop || "30s",
  },
};
if (warmupArrival.enabled && warmupBatches.length > 0) {
  scenarios.capacity_warmup = {
    executor: "constant-arrival-rate",
    exec: "warmup",
    rate: warmupBatches.length,
    timeUnit: warmupArrival.timeUnit || `${warmupArrival.durationSeconds}s`,
    duration: `${warmupArrival.durationSeconds}s`,
    preAllocatedVUs: Number(__ENV.CAPACITY_PRE_ALLOCATED_VUS || arrival.preAllocatedVUs || 1),
    maxVUs: Number(__ENV.CAPACITY_MAX_VUS || arrival.maxVUs || 1),
    gracefulStop: "0s",
  };
}

export const options = {
  scenarios,
  thresholds: {
    dropped_iterations: [`count<=${Number(__ENV.MAX_DROPPED_ITERATIONS || 0)}`],
    http_req_failed: [{ threshold: `rate<=${Number(__ENV.MAX_ERROR_RATE || 0.05)}`, abortOnFail: true, delayAbortEval: "60s" }],
  },
};

function executeBatch(sourceBatches, phase, measured) {
  const batch = iterationBatch(sourceBatches);
  if (!batch) {
    return;
  }
  waitForSchedule(batch, phase);

  if (measured) {
    scheduledReadings.add(batch.length);
    startedReadings.add(batch.length);
    batchItems.add(batch.length);
  }

  const readings = batch.map(payloadFor);
  const first = readings[0];
  const token = tokenBySource.get(first.sourceId) || __ENV.SOURCE_TOKEN;
  if (!token) {
    unknownReadings.add(readings.length);
    throw new Error(`No source token supplied for source ${first.sourceId}.`);
  }

  let attempt = 0;
  while (attempt <= maxRetries) {
    if (attempt > 0) {
      retryReadings.add(readings.length);
    }
    transportAttempts.add(readings.length);

    const response = http.post(
      `${ingestionBaseUrl}/ingestion/readings/batch`,
      JSON.stringify({ readings }),
      {
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${token}`,
          "X-Correlation-Id": `capacity-${plan.runId}-${plan.workerIndex}-${exec.scenario.iterationInTest}-${attempt}`,
        },
        timeout: __ENV.REQUEST_TIMEOUT || "30s",
      },
    );
    batchLatency.add(response.timings.duration);

    const statusOk = check(response, {
      "batch accepted by application": (r) => r.status === 202,
    });
    if (statusOk) {
      const body = response.json();
      if (measured) {
        recordApplicationOutcome(batch, body);
        emitOutcome(batch, attempt, body, "accepted");
        observeVisibility(batch[0], Date.now());
      }
      return;
    }

    const retryable = response.status === 0 || response.error_code;
    if (!retryable || attempt === maxRetries) {
      if (measured) {
        unknownReadings.add(readings.length);
        emitOutcome(batch, attempt, null, "unknown");
      }
      return;
    }
    attempt += 1;
  }
}

export function warmup() {
  executeBatch(warmupBatches, "warmup", false);
}

export function measurement() {
  executeBatch(batches, "measurement", true);
}

export default function () {
  measurement();
}
