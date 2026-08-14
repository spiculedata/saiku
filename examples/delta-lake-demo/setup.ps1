# Delta Lake demo — Saiku-side setup (Windows).
# Prepares a dedicated saiku-home wired to the Trino/Delta stack:
#   1. downloads the Trino JDBC driver into <home>/plugins/
#   2. stages the Mondrian schema + datasource descriptor
# Usage:  ./setup.ps1 [-Home2 .\delta-home] [-TrinoJdbcVersion 476]
param(
    [string]$Home2 = (Join-Path $PSScriptRoot "delta-home"),
    [string]$TrinoJdbcVersion = "476"
)
$ErrorActionPreference = "Stop"

$abs = (New-Item -ItemType Directory -Force $Home2).FullName
New-Item -ItemType Directory -Force (Join-Path $abs "plugins") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $abs "data") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $abs "repository\data\unknown\datasources") | Out-Null

$jar = Join-Path $abs "plugins\trino-jdbc-$TrinoJdbcVersion.jar"
if (-not (Test-Path $jar)) {
    Write-Host "Downloading trino-jdbc $TrinoJdbcVersion ..."
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/io/trino/trino-jdbc/$TrinoJdbcVersion/trino-jdbc-$TrinoJdbcVersion.jar" -OutFile $jar
}

Copy-Item (Join-Path $PSScriptRoot "saiku\LakehouseSales.xml") (Join-Path $abs "data\LakehouseSales.xml") -Force

$sds = Get-Content -Raw (Join-Path $PSScriptRoot "saiku\delta.sds")
# Mondrian connect strings want forward slashes on Windows too.
$sds.Replace("@SAIKU_HOME@", $abs.Replace("\", "/")) |
    Out-File -Encoding utf8 (Join-Path $abs "repository\data\unknown\datasources\delta.sds")

Write-Host ""
Write-Host "saiku-home ready at: $abs"
Write-Host "Start Saiku with:"
Write-Host "  java -Dsaiku.allowDefaultAdmin=true -jar <saiku-jar> serve --port 8080 --home `"$abs`""
