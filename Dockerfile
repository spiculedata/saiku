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
#
# Base image is DIGEST-PINNED (#1989 / #1919 item 18a). The tag is kept
# alongside the digest for humans; the digest is what actually resolves, so a
# re-tagged or tampered upstream can't change what we build on. To roll the
# base forward, resolve the new multi-arch index digest and update both here:
#   docker buildx imagetools inspect eclipse-temurin:21-jre-noble
FROM eclipse-temurin:25-jre-noble@sha256:b573af9e331196fbc42e246da4df24df9b6c556c73e7efddfde0511f1c9508c5
ARG JAR_PATH=build-context/saiku.jar
ARG OTEL_AGENT_VERSION=2.28.1
ARG OTEL_AGENT_SHA256=faa89bdeebf9b1f52be4a4374689176717b02a59df2d8f8b6eb9aa39f9292589

# Non-root runtime user (#1989, CWE-250). uid/gid are FIXED and DOCUMENTED at
# 10001:10001 so operators who bind-mount a host directory for saiku-home can
# `chown 10001:10001` it deterministically across upgrades. Do NOT change these
# numbers without a migration note — existing homes are chowned to them.
#
# HOME is /home/saiku (created + owned by the user), NOT /app: /app and the jar
# stay root-owned (see the chown note below), so a HOME there would be
# read-only. The JVM writes per-user scratch under HOME (e.g. ~/.java/fonts for
# AWT font caching used by chart/PDF/XLS export rendering); it needs a writable
# home of its own.
ARG SAIKU_UID=10001
ARG SAIKU_GID=10001
RUN groupadd --gid "${SAIKU_GID}" saiku \
    && useradd --uid "${SAIKU_UID}" --gid "${SAIKU_GID}" \
       --home-dir /home/saiku --create-home --shell /usr/sbin/nologin saiku

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

# JVM safety defaults (#1989 / #1919 item 18a). Set via JAVA_TOOL_OPTIONS so
# the JVM applies them regardless of how it is launched, and so an operator's
# `-e JAVA_OPTS=...` (which the entrypoint MERGES onto the command line, not
# replaces) adds to rather than erases them. Command-line flags win over
# JAVA_TOOL_OPTIONS for the same option, so an operator can still override an
# individual flag via JAVA_OPTS. Caveat: passing `-e JAVA_TOOL_OPTIONS=...`
# REPLACES this value (env vars don't merge) — override individual flags via
# JAVA_OPTS instead. Note the JVM prints "Picked up JAVA_TOOL_OPTIONS: ..." to
# stderr on every launch; that line is expected and benign.
#   -XX:+ExitOnOutOfMemoryError  fail fast on OOM so the orchestrator restarts
#                                a clean JVM instead of limping on a corrupt heap
#   -XX:MaxRAMPercentage=75      size the heap from the container memory limit
ENV JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=75"

# Ownership, scoped tightly (#1989 S1 review). ONLY /app/saiku-home (the write
# path — data, repository, sessions, logs, plugins, and conf/secret.key) is
# handed to the runtime uid. Everything the JVM merely EXECUTES stays root-owned
# and world-readable: /app/saiku.jar and /opt/saiku/otel/*.jar keep their
# COPY/RUN defaults (0644, uid 0). That means a code-exec-as-saiku compromise
# (the 4.8.0 RCE class this hardening defends against) cannot rewrite or unlink
# the very jar/agent it runs from and persist across `docker restart`.
#
# The chown must precede the VOLUME line: Docker seeds a fresh (empty)
# named/anonymous volume from the image mount-point, carrying its ownership +
# perms across. A chown AFTER VOLUME would not stick. We pre-create
# /app/saiku-home so its ownership is what a new volume inherits. (Bind mounts
# and PRE-EXISTING volumes are NOT chowned by Docker — see the upgrade note in
# CHANGELOG; the entrypoint also fails closed if the home is unusable.)
RUN mkdir -p /app/saiku-home \
    && chown "${SAIKU_UID}:${SAIKU_GID}" /app/saiku-home

VOLUME ["/app/saiku-home"]
EXPOSE 8080

# Liveness probe. /rest/saiku/info is anonymous (security="none"), so no
# credentials are needed. Use HEAD (--head): InfoResource added a dedicated
# HEAD handler in saiku#866 precisely for monitors — a GET walks the plugin
# directory. curl ships in the Ubuntu-noble base (already used above to fetch
# the OTel agent). start-period is generous: first boot seeds saiku-home + runs
# the H2 FoodMart bootstrap (~30s, ~200MB) before the port answers.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD curl -fsS -o /dev/null --head http://localhost:8080/rest/saiku/info || exit 1

# Drop to the non-root user for the actual runtime. Everything above ran as
# root (package install, OTel download, chown); nothing below needs it.
USER saiku

ENTRYPOINT ["/usr/local/bin/saiku-entrypoint"]
CMD ["serve"]
