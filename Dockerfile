# Saiku demo image — bundles the launcher (REST + UI + native MCP on :8080)
# in a single container. Since #878 the MCP endpoint lives inside saiku-webapp
# at /rest/saiku/api/mcp; MCP hosts (Claude Desktop, Cursor, …) authenticate
# with per-user Basic credentials over the same chain as the AI REST API.
# No separate stdio binary required.
#
# Build flow (release.yml / ci.yml):
#   1. The jar job builds the launcher fat JAR under saiku-launcher/target/.
#   2. The docker job stages it into ./build-context/saiku.jar.
#   3. This Dockerfile COPYs it in. No Maven runs at image build —
#      avoids needing GH Packages auth inside the container.
FROM eclipse-temurin:21-jre-noble
ARG JAR_PATH=build-context/saiku.jar
ARG OTEL_AGENT_VERSION=2.28.1
ARG OTEL_AGENT_SHA256=faa89bdeebf9b1f52be4a4374689176717b02a59df2d8f8b6eb9aa39f9292589
WORKDIR /app

COPY ${JAR_PATH} /app/saiku.jar
COPY docker/saiku-entrypoint /usr/local/bin/saiku-entrypoint
RUN chmod +x /usr/local/bin/saiku-entrypoint

# OpenTelemetry Java agent — side-loaded, only attached at runtime when
# OTEL_EXPORTER_OTLP_ENDPOINT is set (see saiku-entrypoint). Pinned by
# checksum so a tampered Maven Central response can't slip in a different
# binary. See docs/observability.md for the runtime activation contract.
RUN set -eux; \
    mkdir -p /opt/saiku/otel; \
    curl -fsSL -o /opt/saiku/otel/opentelemetry-javaagent.jar \
      "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar"; \
    echo "${OTEL_AGENT_SHA256}  /opt/saiku/otel/opentelemetry-javaagent.jar" | sha256sum -c -

ENV SAIKU_HOME=/app/saiku-home

VOLUME ["/app/saiku-home"]
EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/saiku-entrypoint"]
CMD ["serve"]
