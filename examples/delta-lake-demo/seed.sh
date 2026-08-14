#!/usr/bin/env sh
# Seed the Delta tables through Trino. Idempotent (CREATE IF NOT EXISTS).
set -e
echo "Seeding delta.sales.fact_orders from tpch.tiny ..."
docker exec -i delta-trino trino --output-format=NULL < "$(dirname "$0")/seed.sql"
docker exec delta-trino trino --execute "SELECT count(*) AS rows FROM delta.sales.fact_orders"
echo "Seed complete."
