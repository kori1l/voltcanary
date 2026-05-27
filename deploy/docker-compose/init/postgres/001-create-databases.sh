#!/bin/sh
set -eu

databases="${POSTGRES_ADDITIONAL_DATABASES:-voltcanary_identity voltcanary_device voltcanary_alerts voltcanary_simulator voltcanary_ai_agent}"

for database in $databases; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
SELECT 'CREATE DATABASE "$database"'
WHERE NOT EXISTS (
  SELECT FROM pg_database WHERE datname = '$database'
)
\gexec
EOSQL
done
