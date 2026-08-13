# Seed the Iceberg tables through Trino. Idempotent (CREATE IF NOT EXISTS).
$ErrorActionPreference = "Stop"
$sql = Get-Content -Raw (Join-Path $PSScriptRoot "seed.sql")
Write-Host "Seeding iceberg.sales.fact_orders from tpch.tiny ..."
$sql | docker exec -i lakehouse-trino trino --output-format=NULL
docker exec lakehouse-trino trino --execute "SELECT count(*) AS rows FROM iceberg.sales.fact_orders"
Write-Host "Seed complete."
