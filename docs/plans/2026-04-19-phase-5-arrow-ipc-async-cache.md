# Phase 5: Arrow IPC wire format + async query API + durable cache

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Swap Saiku's primary query payload from the row-of-objects JSON
cellset to Apache Arrow IPC, fold in an async execute API, and back the
executor with a disk-backed Arrow cache. Keep the JSON contract alive
for exports, drillthrough CSV, and older clients via content negotiation.

**Architecture:** Server writes Arrow IPC when `Accept: application/vnd.apache.arrow.stream`, JSON otherwise — same `QueryResource.execute` entry point. An `AsyncQueryService` wraps the synchronous executor so clients get `{queryId, status}` back immediately and poll for the stream. A `SaikuQueryCache` sits in front of the executor, keyed on a hash of the normalized ThinQuery, value = Arrow buffer on disk under `<saiku-home>/cache/`. Client adds a thin `parseArrowExecute(buffer) → QueryResult` adapter so `parseCellset` / `CellsetTable` / `ChartView` stay untouched in pass 1.

**Tech Stack:** arrow-java 15.x (backend), apache-arrow 15.x (frontend, lazy-loaded), Jersey 3 `MessageBodyWriter<CellSet>` + `@Produces("application/vnd.apache.arrow.stream")`, Caffeine for in-memory eviction metadata, existing H2-backed scheduler OK for status polling (no new db).

---

## Cellset → Arrow schema

One RecordBatch per result. Column-header rows, `rowHeaderColCount`, runtime, width, height, MDX, query name — all pushed into schema-level metadata (`Map<String,String>`) as a canonical JSON blob under the key `saiku.cellset`.

Columns in order:
- `r0_value`, `r0_uniqueName`, `r0_dimension`, `r0_hierarchy`, `r0_level` — dictionary-encoded string, one group per row-header column, i=0..rowHeaderColCount-1.
- `c0_raw`, `c0_fmt` — Float64 nullable + dictionary-encoded string (only populated when `fmt ≠ raw.toString()`) — one group per data column, j=0..dataColCount-1.

Null policy: `rX_value` is the empty string for "row_null" cells (matches legacy), `cY_raw` is null for empty data cells, `cY_fmt` is the server-rendered display string.

---

## Task 1 — Add arrow-java to the backend build

**Files:**
- Modify: `saiku-core/saiku-bom/pom.xml:<properties>` (add `<arrow.version>15.0.2</arrow.version>`)
- Modify: `saiku-core/saiku-bom/pom.xml:<dependencyManagement>` (import `arrow-vector`, `arrow-memory-netty`, `arrow-format`)
- Modify: `saiku-core/saiku-service/pom.xml` (add runtime deps on `arrow-vector` + `arrow-memory-netty`)

**Step 1:** Confirm JDK ≥ 11 and open-modules for Arrow's Netty allocator in `saiku-launcher/src/main/resources/saiku-launcher-jvm-args.txt` (create if missing): add `--add-opens=java.base/java.nio=ALL-UNNAMED` to the Picocli exec args.

**Step 2:** Build.

Run: `mvn -pl saiku-core/saiku-service -am compile -DskipTests -q`
Expected: `BUILD SUCCESS`, `arrow-vector-15.0.2.jar` resolves.

**Step 3:** Commit.

```bash
git add saiku-core/saiku-bom/pom.xml saiku-core/saiku-service/pom.xml saiku-launcher/src/main/resources/saiku-launcher-jvm-args.txt
git commit -m "build(arrow): pull arrow-vector + memory-netty into the service classpath"
```

---

## Task 2 — Write `ArrowCellsetWriter` in Java

**Files:**
- Create: `saiku-core/saiku-service/src/main/java/org/saiku/olap/result/ArrowCellsetWriter.java`
- Create: `saiku-core/saiku-service/src/test/java/org/saiku/olap/result/ArrowCellsetWriterTest.java`

**Step 1 (TDD — failing test):** Write a test that executes a 3-row × 2-measure query against the `foodmart_hsql` test DB, pipes the result through `ArrowCellsetWriter`, reads the bytes back with `ArrowStreamReader`, and asserts:
- 1 RecordBatch, 3 rows.
- Metadata key `saiku.cellset` contains JSON with `rowHeaderColCount=1` and `columnHeaderRows.length=1`.
- Column `c0_raw` has the three numeric values.
- Column `r0_value` has "USA","CA","OR" (or equivalent).

Run: `mvn -pl saiku-core/saiku-service test -Dtest=ArrowCellsetWriterTest -q`
Expected: FAIL — `ArrowCellsetWriter` not found.

**Step 2:** Implement the writer. Use `RootAllocator` (per-call, try-with-resources), `DictionaryProvider.MapDictionaryProvider`, and `ArrowStreamWriter(VectorSchemaRoot, provider, Channels.newChannel(out))`. Build the schema from the CellSet axes before writing the batch.

Skeleton:

```java
public final class ArrowCellsetWriter {
  public void write(CellSet cellSet, ThinQuery query, OutputStream out) throws IOException {
    try (BufferAllocator allocator = new RootAllocator()) {
      CellsetShape shape = CellsetShape.of(cellSet);                       // NEW helper
      Schema schema = buildSchema(shape, query);                           // metadata + fields
      try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator);
           ArrowStreamWriter writer = new ArrowStreamWriter(root, null, Channels.newChannel(out))) {
        writer.start();
        fillRoot(root, cellSet, shape);
        writer.writeBatch();
        writer.end();
      }
    }
  }
  // buildSchema / fillRoot private helpers — see ArrowStreamWriter docs.
}
```

**Step 3:** Rerun test. Iterate until green.

**Step 4:** Commit.

```bash
git add saiku-core/saiku-service/src/main/java/org/saiku/olap/result/ArrowCellsetWriter.java \
        saiku-core/saiku-service/src/test/java/org/saiku/olap/result/ArrowCellsetWriterTest.java
git commit -m "feat(arrow): cellset → Arrow IPC writer with axis metadata"
```

---

## Task 3 — Content-negotiate the execute endpoint

**Files:**
- Modify: `saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/Query2Resource.java` (`execute` method)
- Create: `saiku-core/saiku-web/src/main/java/org/saiku/web/rest/providers/ArrowMessageBodyWriter.java`
- Register provider in `saiku-webapp/src/main/webapp/WEB-INF/classes/META-INF/services/jakarta.ws.rs.ext.MessageBodyWriter` (or via `SaikuJerseyApplication.register`).

**Step 1 (TDD):** integration test that POSTs an execute with `Accept: application/vnd.apache.arrow.stream` and asserts:
- Response `Content-Type: application/vnd.apache.arrow.stream`.
- Body starts with Arrow magic (`ARROW1\0\0` after the 4-byte length).
- `ArrowStreamReader` can read one RecordBatch.

Run: `mvn -pl saiku-core/saiku-web test -Dtest=Query2ResourceArrowTest -q`
Expected: FAIL — endpoint still returns JSON.

**Step 2:** Add `@Produces({"application/vnd.apache.arrow.stream", "application/json"})` to `execute`. Inside the method, check `@Context HttpHeaders` for Accept, branch to `new ArrowCellsetWriter().write(cellSet, query, outputStream)` for Arrow, legacy `RestUtil.convert(cellSet)` for JSON.

**Step 3:** Rerun tests — both JSON and Arrow variants must pass.

**Step 4:** Commit.

```bash
git add saiku-core/saiku-web/src/main/java/org/saiku/web/rest/ \
        saiku-webapp/src/main/webapp/WEB-INF/classes/META-INF/services/
git commit -m "feat(arrow): content-negotiate execute — Arrow stream when asked, JSON otherwise"
```

---

## Task 4 — Async execute: `/execute-async` + `/status` + `/result`

**Files:**
- Create: `saiku-core/saiku-service/src/main/java/org/saiku/service/async/AsyncQueryService.java`
- Create: `saiku-core/saiku-service/src/main/java/org/saiku/service/async/AsyncQueryHandle.java`
- Modify: `saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/Query2Resource.java` (three new routes)
- Config: `saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml` (register `AsyncQueryService` bean with an injected `ThreadPoolExecutor`, core 4 / max 16 / queue 200)

**Step 1 (TDD):**
1. POST `execute-async` → assert 202 + body `{queryId, status: "PENDING"}`.
2. Poll `GET /query/:id/status` until `status == "DONE"`.
3. GET `/query/:id/result` with `Accept: application/vnd.apache.arrow.stream` → Arrow RecordBatch.

Run: `mvn -pl saiku-core/saiku-web test -Dtest=AsyncQueryResourceTest -q`
Expected: FAIL.

**Step 2:** Implement. `AsyncQueryService.submit(query) → AsyncQueryHandle { id, CompletableFuture<CellSet> }`. Store `ConcurrentHashMap<String, AsyncQueryHandle>`, evict on result fetch + 5 min idle. Stream Arrow on result read to avoid buffering.

**Step 3:** `DELETE /query/:id/cancel` — cancel the future + close the underlying OLAP statement.

**Step 4:** Commit.

```bash
git add saiku-core/saiku-service/src/main/java/org/saiku/service/async/ \
        saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/Query2Resource.java \
        saiku-webapp/src/main/webapp/WEB-INF/saiku-beans.xml \
        saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/AsyncQueryResourceTest.java
git commit -m "feat(async): /execute-async + /status + /result + /cancel routes"
```

---

## Task 5 — Disk-backed Arrow cache

**Files:**
- Create: `saiku-core/saiku-service/src/main/java/org/saiku/service/cache/SaikuQueryCache.java`
- Create: `saiku-core/saiku-service/src/main/java/org/saiku/service/cache/QueryCacheKey.java`
- Modify: `saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ThinQueryService.java` (wrap `execute` in `cache.get(key, () -> …)`)
- Props: `saiku-webapp/src/main/resources/saiku.properties` (`saiku.cache.enabled=true`, `saiku.cache.ttl.minutes=30`, `saiku.cache.max.size.bytes=268435456`)

**Step 1 (TDD):** Test that:
1. First execute of query `Q` writes `<saiku-home>/cache/<sha>.arrow` + sidecar `.meta.json`.
2. Second execute with identical `Q` returns the cached bytes (verify timestamp unchanged + a metric counter increments).
3. Bumping the cube metadata version (via `SaikuCubeMetadataVersionService`) invalidates the entry.

Run: `mvn -pl saiku-core/saiku-service test -Dtest=SaikuQueryCacheTest -q`
Expected: FAIL.

**Step 2:** Implement. Key = SHA-256 hex of `Jackson.writeValueAsString(ThinQuery)` with measures/hierarchies sorted deterministically. Store Arrow blob + JSON sidecar `{version, runtimeMs, rows, cubeVersion}`. Caffeine in front for hot entries (LRU); disk behind for durability. Evict entries exceeding `max.size.bytes` LRU-first.

**Step 3:** Commit.

```bash
git add saiku-core/saiku-service/src/main/java/org/saiku/service/cache/ \
        saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ThinQueryService.java \
        saiku-webapp/src/main/resources/saiku.properties \
        saiku-core/saiku-service/src/test/java/org/saiku/service/cache/SaikuQueryCacheTest.java
git commit -m "feat(cache): disk-backed Arrow query cache with cube-version invalidation"
```

---

## Task 6 — Client Arrow reader + adapter

**Files:**
- Modify: `saiku-ui/package.json` (add `apache-arrow@^15.0.0`)
- Create: `saiku-ui/src/lib/api/arrow.ts`
- Modify: `saiku-ui/src/lib/api/query.ts` (`executeQuery`)

**Step 1 (TDD):** Vitest unit test in `saiku-ui/src/lib/api/arrow.test.ts`:
- Load a fixture `.arrow` file (generated by `ArrowCellsetWriterTest` and committed under `saiku-ui/src/test/fixtures/`).
- Assert `parseArrowExecute(buffer)` produces a `QueryResult` with identical `cellset`, `height`, `width`, `runtime` to the JSON equivalent.

Run: `npx vitest run src/lib/api/arrow.test.ts`
Expected: FAIL — `arrow.ts` not present.

**Step 2:** Implement `parseArrowExecute(buffer: ArrayBuffer): QueryResult` using dynamic `await import("apache-arrow")` so the dep doesn't bloat the initial bundle. Reconstruct the legacy `cellset` 2D `CellEntry[][]` from column-header metadata + RecordBatch rows. Keep a `// TODO (phase 5b): skip the rebuild and expose columns directly` marker.

**Step 3:** Update `executeQuery`:

```ts
const res = await fetch(`${REST_BASE}/execute`, {
  method: "POST",
  credentials: "include",
  headers: {
    "Content-Type": "application/json",
    Accept: "application/vnd.apache.arrow.stream, application/json;q=0.1",
  },
  body: JSON.stringify(q),
});
const ct = res.headers.get("content-type") ?? "";
if (ct.includes("arrow")) return parseArrowExecute(await res.arrayBuffer());
return (await res.json()) as QueryResult;
```

**Step 4:** Commit.

```bash
git add saiku-ui/package.json saiku-ui/package-lock.json saiku-ui/src/lib/api/ saiku-ui/src/test/
git commit -m "feat(ui): Arrow-first execute with JSON fallback + parseArrowExecute adapter"
```

---

## Task 7 — Client async flow

**Files:**
- Modify: `saiku-ui/src/lib/api/query.ts` (new `executeQueryAsync`)
- Modify: `saiku-ui/src/lib/stores/query.svelte.ts` (new `asyncThreshold` config; `run()` chooses sync vs async)
- Modify: `saiku-ui/src/lib/views/QueryCanvas.svelte` (progress indicator: elapsed ms + "Cancel" button wired to `DELETE /query/:id/cancel`)

**Step 1:** `executeQueryAsync(q)` returns a Promise that resolves to `QueryResult`. Internally:
1. POST `/execute-async` → `{ queryId }`.
2. Poll `/status` every 500ms with exponential backoff up to 5s.
3. On `DONE`, GET `/result` with Arrow Accept header, parse, return.
4. On `FAILED`, throw with the server's error string.

**Step 2:** `query.run()` uses sync if `hasRunnableShape()` and we have no expectation of a large result; otherwise uses async. Default threshold: always async if `query.async === true` (new toggle in Run dropdown under the chevron).

**Step 3:** QueryCanvas shows `Running… (Xs)` + Cancel button while `query.running`. Cancel calls `cancelQuery(queryId)`.

**Step 4:** Commit.

```bash
git commit -m "feat(ui): async query flow with progress/cancel + Run-menu toggle"
```

---

## Task 8 — Hook drillthrough into Arrow

**Files:**
- Modify: `saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/QueryResource.java` (`/drillthrough`)
- Modify: `saiku-ui/src/lib/api/query.ts` (`drillthrough`)
- Modify: `saiku-ui/src/lib/modals/DrillthroughResultModal.svelte`

**Step 1:** Content-negotiate `/drillthrough` the same way. The Arrow version returns one RecordBatch with typed columns — DrillthroughResultModal can now right-align numerics and benefits most from the size reduction (drillthrough returns raw facts, often 10k+ rows).

**Step 2:** Commit.

```bash
git commit -m "feat(arrow): drillthrough returns Arrow IPC on negotiation"
```

---

## Task 9 — Benchmark + telemetry

**Files:**
- Create: `saiku-core/saiku-service/src/test/java/org/saiku/perf/ArrowVsJsonBenchmark.java` (JMH optional, or a plain `main` that runs a 50k-row query both ways and prints bytes + millis)
- Modify: `saiku-core/saiku-service/src/main/java/org/saiku/service/olap/ThinQueryService.java` (log `bytes=…, format=arrow|json, runtimeMs=…, cacheHit=true|false` at INFO)

**Step 1:** Run the benchmark. Copy the table into the plan under a `## Benchmark results` section below.

**Step 2:** Commit.

```bash
git commit -m "perf(arrow): record bytes/ms delta vs JSON for typical result sizes"
```

---

## Task 10 (optional, next increment) — Retire the JSON path

Only after a full release cycle on Arrow without regressions:
1. Delete `RestUtil.convert(CellSet)` + `QueryResult` row-of-objects representation.
2. Export paths (XLS/CSV/PDF) either (a) rebuild from the cached Arrow or (b) call the OLAP layer directly. Prefer (a) so exports hit the same cache.
3. Update TypeScript types — `CellEntry[][]` disappears, replaced by an Arrow-backed view.

Defer the deletion until after Task 9 confirms the new path stable.

---

## Risks & how to bail out

- **Arrow allocator leaks** → every `RootAllocator` / `BufferAllocator` goes inside `try-with-resources`. Add a reaper that logs non-zero allocated memory on webapp shutdown.
- **Off-heap pressure in the fat-jar** → default Netty direct memory is 16× heap; explicitly cap via `-Dio.netty.maxDirectMemory=268435456` in `saiku-launcher-jvm-args.txt`.
- **Jakarta servlet classloader + apache-arrow on the classpath** → arrow-memory-netty transitive pulls `netty-buffer`; confirm no version clash with jetty-12's netty. If it collides, swap to `arrow-memory-unsafe`.
- **Client bundle growth** → `apache-arrow` must be lazy-imported inside `parseArrowExecute`. Verify with `vite build --sourcemap` that the initial chunk doesn't include `apache-arrow`.
- **Backwards compatibility** → every task keeps the JSON path working. If Arrow breaks in prod, users can set `localStorage.saiku_force_json = "1"` and the client will drop the Arrow Accept header.

---

## Benchmark results

Generated by `ArrowVsJsonBenchmark` on 2026-04-19, JDK 21.0.5, branch `phase-1d-jakarta-springboot`.
Synthetic CellSet (Proxy-backed, no DB) — 1 row-header column with unique city captions, N measures with distinct formatted values. Median of 5 runs after 1 warmup; payload is the raw `ByteArrayOutputStream.size()` for both formats. `ObjectMapper` is constructed inside the timed block on the JSON path to mirror the real per-request allocation in Jersey.

| rows   | cols | format | bytes      | ms   | ratio (bytes) | ratio (ms) |
|--------|------|--------|------------|------|---------------|------------|
| 100    | 4    | json   |      36595 |  2.8 |          1.00 |       1.00 |
| 100    | 4    | arrow  |      18976 |  4.2 |          0.52 |       1.49 |
| 1000   | 8    | json   |     586661 | 12.7 |          1.00 |       1.00 |
| 1000   | 8    | arrow  |     260216 | 14.9 |          0.44 |       1.17 |
| 10000  | 4    | json   |    3864871 | 29.8 |          1.00 |       1.00 |
| 10000  | 4    | arrow  |    1648800 | 32.5 |          0.43 |       1.09 |
| 50000  | 4    | json   |   19750591 | 138.4 |          1.00 |       1.00 |
| 50000  | 4    | arrow  |    8486648 | 192.1 |          0.43 |       1.39 |

**Headline:** Arrow is ~57% smaller on the wire at 10k rows (1.65 MB vs 3.86 MB) and ~57% smaller at 50k rows (8.49 MB vs 19.75 MB). Wire savings are the primary win across every shape (43–52% smaller consistently — dictionary encoding collapses repeated member metadata).

**Surprise / caveat on serialization time:** raw CPU cost of serializing is comparable to or slightly *slower* than hand-rolled Jackson `JsonGenerator` (9–49% slower). `ArrowCellsetWriter` spins up a fresh `RootAllocator` + per-column dictionary bookkeeping per request, and that per-call fixed cost dominates until payloads become very large. Jackson's streaming generator is remarkably fast on unboxed primitives. Net wire benefit still favors Arrow at every scale because network + browser JSON.parse dwarf the server serialize cost, but we should not claim "Arrow is faster to serialize" — **it is smaller, not faster**. Potential follow-ups: pool the allocator across requests; skip dictionary encoding for single-value columns; pre-size vectors from the `CellsetShape`.

JSON overhead is still small in absolute terms for dashboard-tile-sized results (100×4 JSON is 36 KB / 3 ms — fine). The gap in bytes matters most at 10k+ rows, which is exactly where async + caching also earn their keep.

---

## Execution Handoff

**Plan saved to `docs/plans/2026-04-19-phase-5-arrow-ipc-async-cache.md`. Two execution options:**

**1. Subagent-Driven (this session)** — Fresh subagent per task, review between tasks, fast iteration.

**2. Parallel Session (separate)** — Open a new session with executing-plans, batch execution with checkpoints.

**Which approach?**

**If Subagent-Driven chosen:**
- REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
- Stay in this session
- Fresh subagent per task + code review

**If Parallel Session chosen:**
- Guide them to open new session in worktree
- REQUIRED SUB-SKILL: New session uses superpowers:executing-plans
