#!/usr/bin/env sh
# Seed the Iceberg tables through Trino. Idempotent (CREATE IF NOT EXISTS).
set -e
echo "Seeding iceberg.sales.fact_orders from tpch.tiny ..."
docker exec -i lakehouse-trino trino --output-format=NULL < "$(dirname "$0")/seed.sql"
docker exec lakehouse-trino trino --execute "SELECT count(*) AS rows FROM iceberg.sales.fact_orders"
echo "Seed complete."
