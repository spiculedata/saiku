# Phase 5 — Query pipeline (implementation plan)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to execute task-by-task.

**Goal:** Large results and long queries stop feeling painful. Arrow IPC on the wire, async job model with cancellation, Caffeine cache, query profile, Parquet export.

**Base branch:** `development` (post Phase 4 merge).

**Tech stack:** Apache Arrow Java + JS, Caffeine, Spring Boot 4 (already in place from Phase 1 Group D), Server-Sent Events, JDK 21 virtual threads.

**Effort:** 2–3 focused sessions.

---

## Tasks

### Task 5.1 — Arrow IPC transport (server)

New `ArrowCellSetFormatter` alongside the existing `CellSetFormatter`. Serialises Mondrian `CellSet` into Arrow record batches:
- Dimension axes → dictionary-encoded string columns
- Measure axis → typed numeric columns (Float64 / Int64 / Decimal depending on measure type)
- Streams record batches over HTTP chunked responses via `VectorSchemaRoot` + `ArrowStreamWriter`

Content-Type: `application/vnd.apache.arrow.stream`

### Task 5.2 — Arrow transport (client)

Install `apache-arrow` in `saiku-ui`. Replace JSON cellset parsing with Arrow IPC streaming reader. Feed directly into AG Grid's row model with zero-copy where possible.

### Task 5.3 — Async job API

New endpoints:
- `POST /rest/saiku/api/queries` — accepts `{mdx, cubeName}`, returns `{jobId}` immediately (HTTP 202)
- `GET /rest/saiku/api/queries/{jobId}/stream` — SSE with events `{status, batchIndex, metadata}` + chunked Arrow body
- `DELETE /rest/saiku/api/queries/{jobId}` — cancels the underlying `olap4j.Statement.cancel()`
- `GET /rest/saiku/api/queries/{jobId}/profile` — returns the timing breakdown

### Task 5.4 — Virtual-thread executor

`Executors.newVirtualThreadPerTaskExecutor()` for query submission. One thread per in-flight query; JDK 21 handles scheduling. No pool tuning required.

### Task 5.5 — Caffeine query cache

`com.github.ben-manes.caffeine:caffeine` added to BOM. Wrap `ThinQueryService`:
- Key: `(schemaVersion, mdxNormalised, userRoles[])`
- Value: Arrow bytes + metadata
- Eviction: size-based (configurable max-bytes) + TTL (default 10 min)
- Metrics: exposed at `/actuator/metrics/saiku.query.cache.{hit,miss,evict}`

### Task 5.6 — Query profile drawer (UI)

New UI component shows per-query:
- Generated SQL (from Mondrian's SQL log; add a `MondrianLogInterceptor` to capture)
- Timing breakdown: MDX parse / plan / SQL gen / SQL exec / format
- Cache hit/miss
- Row count

### Task 5.7 — Cancel button (UI)

Button on the running-query spinner sends `DELETE /rest/saiku/api/queries/{jobId}`. Server invokes `Statement.cancel()`. Playwright test asserts cancellation completes within 1 second and the DB connection is returned to the pool.

### Task 5.8 — Back-pressure (max cells)

Server enforces `saiku.query.max-cells` (default 1,000,000). Above limit, the query still runs but the SSE stream ends with `{status: "too-large", downloadUrl}` and the client offers a Parquet export instead of rendering.

### Task 5.9 — Parquet export

`GET /rest/saiku/api/queries/{jobId}/export?format=parquet`. Converts the Arrow result to Parquet via `parquet-arrow`. Streams the Parquet file with `Content-Disposition: attachment`.

Also expose `format=csv` and `format=xlsx` (POI, already in the tree).

---

## Exit criteria

- 1M-cell result streams incrementally into the grid; first rows visible within 500ms
- Cancel button kills upstream Mondrian query within 1 second (Playwright verified)
- Profile drawer shows SQL + timings on every query
- Cache hit rate visible in `/actuator/metrics`
- Parquet / CSV / XLSX export endpoints work and produce correct files
- Arrow wire format saves > 50% bytes vs. the legacy JSON format on a typical sales cube query

## Risk-ordered task list

| # | Risk | Reversible? | Ship independently? |
|---|------|-------------|---------------------|
| 5.1 Arrow server | Med | Yes | Yes |
| 5.2 Arrow client | Med | Yes | Pair with 5.1 |
| 5.3 Async API | Low | Yes | Yes |
| 5.4 Virtual threads | Low | Yes | Yes |
| 5.5 Caffeine cache | Low | Yes | Yes |
| 5.6 Profile drawer | Low | Yes | Yes |
| 5.7 Cancel button | Low | Yes | Yes |
| 5.8 Back-pressure | Med (behaviour change) | Yes | After 5.9 |
| 5.9 Parquet export | Low | Yes | Yes |
