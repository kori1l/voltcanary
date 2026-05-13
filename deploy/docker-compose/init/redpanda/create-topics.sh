#!/bin/sh
set -eu

BROKERS="${KAFKA_BOOTSTRAP_SERVERS:-redpanda:9092}"
PARTITIONS="${KAFKA_TOPIC_PARTITIONS:-3}"
REPLICAS="${KAFKA_TOPIC_REPLICAS:-1}"

topics="
telemetry.raw.v1
telemetry.validated.v1
anomaly.detected.v1
risk-score.changed.v1
alert.created.v1
telemetry.raw.v1.dlq
telemetry.validated.v1.dlq
anomaly.detected.v1.dlq
risk-score.changed.v1.dlq
alert.created.v1.dlq
"

for topic in $topics; do
  rpk topic create "$topic" \
    -X "brokers=$BROKERS" \
    --partitions "$PARTITIONS" \
    --replicas "$REPLICAS" || true
done

rpk topic list -X "brokers=$BROKERS"
