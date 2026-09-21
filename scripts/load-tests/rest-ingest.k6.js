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
const sourceTokenPath = __ENV.SOURCE_TOKENS_PATH || `${runDirectory}/provisioning-secrets.local.json`;
const plan = JSON.parse(open(`${runDirectory}/generation-manifest.json`));
const sourceTokens = JSON.parse(open(sourceTokenPath));
const tokenBySource = new Map();
for (const item of sourceTokens.sourceTokens || []) {
  if (item.externalId) {
    tokenBySource.set(item.externalId, item.token);
  }
  if (item.apiDataSourceId) {
    tokenBySource.set(item.apiDataSourceId, item.token);
  }
}
const arrival = plan.arrivalSchedule || {};
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

const batches = new SharedArray("capacity-rest-batches", () => batchesFor(plan.readings || [], Number(plan.accounting.batchSize || 1)));
const wallClockStartMillis = Number(__ENV.SCHEDULE_WALL_CLOCK_START_MS || Date.now());
const maxRetries = Number(__ENV.MAX_RETRIES || arrival.maxRetries || 1);

function iterationBatch() {
  const iteration = exec.scenario.iterationInTest;
  const batch = batches[iteration];
  if (!batch) {
    droppedReadings.add(Number(plan.accounting.batchSize || 1));
    return null;
  }
  return batch;
}

function waitForSchedule(batch) {
  if (__ENV.DISABLE_SCHEDULE_WAIT === "1") {
    return;
  }
  const firstOffsetMillis = Math.min(...batch.map((reading) => Number(reading.recordedAtOffsetSeconds) * 1000));
  const elapsedMillis = Date.now() - wallClockStartMillis;
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
  }));
}

export const options = {
  scenarios: {
    capacity_rest: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.K6_RATE || arrival.rate || batches.length),
      timeUnit: __ENV.K6_TIME_UNIT || arrival.timeUnit || "1s",
      duration: __ENV.K6_DURATION || arrival.duration || "1s",
      preAllocatedVUs: Number(__ENV.K6_PRE_ALLOCATED_VUS || arrival.preAllocatedVUs || 1),
      maxVUs: Number(__ENV.K6_MAX_VUS || arrival.maxVUs || 1),
      gracefulStop: __ENV.K6_GRACEFUL_STOP || arrival.gracefulStop || "30s",
    },
  },
  thresholds: {
    dropped_iterations: [`count<=${Number(__ENV.MAX_DROPPED_ITERATIONS || 0)}`],
  },
};

export default function () {
  const batch = iterationBatch();
  if (!batch) {
    return;
  }
  waitForSchedule(batch);

  scheduledReadings.add(batch.length);
  startedReadings.add(batch.length);
  batchItems.add(batch.length);

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
      recordApplicationOutcome(batch, body);
      emitOutcome(batch, attempt, body, "accepted");
      return;
    }

    const retryable = response.status === 0 || response.error_code;
    if (!retryable || attempt === maxRetries) {
      unknownReadings.add(readings.length);
      emitOutcome(batch, attempt, null, "unknown");
      return;
    }
    attempt += 1;
  }
}
