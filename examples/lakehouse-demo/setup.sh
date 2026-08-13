#!/usr/bin/env sh
# Lakehouse demo — Saiku-side setup (macOS / Linux).
# Prepares a dedicated saiku-home wired to the Trino/Iceberg stack:
#   1. downloads the Trino JDBC driver into <home>/plugins/
#   2. stages the Mondrian schema + datasource descriptor
# Usage:  ./setup.sh [home-dir] [trino-jdbc-version]
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
HOME_DIR="${1:-$DIR/lakehouse-home}"
V="${2:-476}"

mkdir -p "$HOME_DIR/plugins" "$HOME_DIR/data" "$HOME_DIR/repository/data/unknown/datasources"
ABS="$(cd "$HOME_DIR" && pwd)"

JAR="$ABS/plugins/trino-jdbc-$V.jar"
if [ ! -f "$JAR" ]; then
    echo "Downloading trino-jdbc $V ..."
    curl -fsSL -o "$JAR" "https://repo1.maven.org/maven2/io/trino/trino-jdbc/$V/trino-jdbc-$V.jar"
fi

cp "$DIR/saiku/LakehouseSales.xml" "$ABS/data/LakehouseSales.xml"
sed "s|@SAIKU_HOME@|$ABS|g" "$DIR/saiku/lakehouse.sds" \
    > "$ABS/repository/data/unknown/datasources/lakehouse.sds"

echo
echo "saiku-home ready at: $ABS"
echo "Start Saiku with:"
echo "  java -Dsaiku.allowDefaultAdmin=true -jar <saiku-jar> serve --port 8080 --home \"$ABS\""
