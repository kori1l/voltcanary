#!/bin/sh
set -eu

mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

for bucket in csv-uploads model-artifacts reports exports; do
  mc mb --ignore-existing "local/$bucket"
done

mc ls local
