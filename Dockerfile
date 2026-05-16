# Saiku demo image — bundles the launcher (REST + UI on :8080) and the
# MCP wrapper (stdio JSON-RPC) in one container so Claude Desktop /
# Cursor / Cline can wire to a running Saiku via a single
# `docker exec -i <name> saiku-mcp` invocation.
#
# Build flow (release.yml / ci.yml):
#   1. The jar job builds both fat JARs under saiku-launcher/target/
#      and saiku-mcp/target/.
#   2. The docker job stages them into ./build-context/ as
#      saiku.jar and saiku-mcp.jar.
#   3. This Dockerfile COPYs them in. No Maven runs at image build —
#      avoids needing GH Packages auth inside the container.
FROM eclipse-temurin:21-jre-noble
ARG JAR_PATH=build-context/saiku.jar
ARG MCP_JAR_PATH=build-context/saiku-mcp.jar
WORKDIR /app

COPY ${JAR_PATH} /app/saiku.jar
COPY ${MCP_JAR_PATH} /app/saiku-mcp.jar
COPY docker/saiku-entrypoint /usr/local/bin/saiku-entrypoint
COPY docker/saiku-mcp /usr/local/bin/saiku-mcp
RUN chmod +x /usr/local/bin/saiku-entrypoint /usr/local/bin/saiku-mcp

ENV SAIKU_HOME=/app/saiku-home \
    SAIKU_URL=http://localhost:8080 \
    SAIKU_USER=admin \
    SAIKU_PASS=admin

VOLUME ["/app/saiku-home"]
EXPOSE 8080

# Default: run the launcher. Override with `mcp` to run the stdio
# MCP server against an external SAIKU_URL. The intended demo flow is:
#   docker run -d -p 8080:8080 --name saiku-demo ghcr.io/spiculedata/saiku
#   # then in Claude Desktop config:
#   command: docker
#   args:    ["exec", "-i", "saiku-demo", "saiku-mcp"]
ENTRYPOINT ["/usr/local/bin/saiku-entrypoint"]
CMD ["serve"]
