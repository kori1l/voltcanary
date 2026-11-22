#!/bin/sh
set -eu

BROKERS="${KAFKA_BOOTSTRAP_SERVERS:-redpanda:9092}"
PARTITIONS="${KAFKA_TOPIC_PARTITIONS:-3}"
REPLICAS="${KAFKA_TOPIC_REPLICAS:-1}"

topics="
telemetry.raw.v1
telemetry.validated.v1
device-registry.changed.v1
maintenance-window.changed.v1
identity.account-lifecycle.v1
anomaly.detected.v1
risk-score.changed.v1
alert.created.v1
source.runtime-config.v1
source.binding-schema.v1
source.processing-config.v1
telemetry.measurements.accepted.v1
telemetry.observations.accepted.v1
source.processing-config.dlq.v1
analytics.variable-measurements.dlq.v1
telemetry.raw.dlq.v1
analytics.telemetry.validated.dlq.v1
device-registry.changed.v1.dlq
maintenance-window.changed.dlq.v1
identity.account-lifecycle.v1.dlq
anomaly.detected.dlq.v1
risk-score.changed.dlq.v1
alert.created.v1.dlq
"

for topic in $topics; do
  if [ "$topic" = "source.runtime-config.v1" ] || [ "$topic" = "source.processing-config.v1" ] || [ "$topic" = "source.binding-schema.v1" ]; then
    rpk topic create "$topic" \
      -X "brokers=$BROKERS" \
      --partitions "$PARTITIONS" \
      --replicas "$REPLICAS" \
      --topic-config cleanup.policy=compact || true
    continue
  fi

  rpk topic create "$topic" \
    -X "brokers=$BROKERS" \
    --partitions "$PARTITIONS" \
    --replicas "$REPLICAS" || true
done

rpk topic list -X "brokers=$BROKERS"
