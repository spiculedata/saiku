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
WORKDIR /app

COPY ${JAR_PATH} /app/saiku.jar
COPY docker/saiku-entrypoint /usr/local/bin/saiku-entrypoint
RUN chmod +x /usr/local/bin/saiku-entrypoint

ENV SAIKU_HOME=/app/saiku-home

VOLUME ["/app/saiku-home"]
EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/saiku-entrypoint"]
CMD ["serve"]
