# Seed the Delta tables through Trino. Idempotent (CREATE IF NOT EXISTS).
$ErrorActionPreference = "Stop"
$sql = Get-Content -Raw (Join-Path $PSScriptRoot "seed.sql")
Write-Host "Seeding delta.sales.fact_orders from tpch.tiny ..."
$sql | docker exec -i delta-trino trino --output-format=NULL
docker exec delta-trino trino --execute "SELECT count(*) AS rows FROM delta.sales.fact_orders"
Write-Host "Seed complete."
