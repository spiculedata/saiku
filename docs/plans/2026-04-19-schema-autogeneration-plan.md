# Schema Auto-Generation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Generate Mondrian schemas from a JDBC data source via a deterministic introspection pass plus an optional LLM enrichment pass, with a two-pane review UI and delta-aware re-runs.

**Architecture:** Three layers. `saiku-service` owns pure-Java pipeline stages (`JdbcIntrospector` → `SchemaInferrer` → `LlmEnricher` → `DeltaReconciler` → `MondrianSchemaWriter`). `saiku-web` exposes a session-based REST API. `saiku-ui` ships a Svelte two-pane editor. LLM access is behind an SPI with a `NoopProvider` fallback so the feature works air-gapped.

**Tech Stack:** Java 17, JAX-RS (Jersey), existing Mondrian schema object model, JUnit 5, Jackson; Svelte 5 / SvelteKit on the UI.

**Reference design:** `docs/plans/2026-04-19-schema-autogeneration-design.md`

---

## Conventions

- Java package root: `org.saiku.service.schema.generate` (service), `org.saiku.web.rest.resources.schemagen` (REST).
- Svelte route: `src/routes/admin/schema-generator/[dataSourceId]/`.
- Test roots mirror source roots. Use JUnit 5.
- TDD everywhere — failing test first, minimal code, green, commit.
- Golden-file fixtures live under `saiku-core/saiku-service/src/test/resources/schemagen/fixtures/`.
- Commit messages use Conventional Commits (`feat(schemagen): …`, `test(schemagen): …`).

---

## Phase A — Deterministic core

Goal: pure-Java pipeline from a `DbModel` in, a `DraftSchema` out, serialisable to Mondrian XML. No JDBC, no LLM, no HTTP yet.

### Task A1: `DbModel` value types

**Files:**
- Create: `saiku-core/saiku-service/src/main/java/org/saiku/service/schema/generate/model/DbModel.java`
- Create: `saiku-core/saiku-service/src/main/java/org/saiku/service/schema/generate/model/DbTable.java`
- Create: `saiku-core/saiku-service/src/main/java/org/saiku/service/schema/generate/model/DbColumn.java`
- Create: `saiku-core/saiku-service/src/main/java/org/saiku/service/schema/generate/model/DbForeignKey.java`
- Test: `saiku-core/saiku-service/src/test/java/org/saiku/service/schema/generate/model/DbModelTest.java`

**Step 1: Failing test** — assert `DbModel.of(List.of(table)).tableByName("orders")` returns the table; `tableByName("missing")` returns empty optional; equality by value.

**Step 2: Run** — `mvn -pl saiku-core/saiku-service test -Dtest=DbModelTest` → FAIL (class missing).

**Step 3: Implement** — Java records: `DbModel(List<DbTable> tables)`, `DbTable(String schema, String name, List<DbColumn> columns, List<DbForeignKey> foreignKeys, Long rowCountEstimate)`, `DbColumn(String name, JDBCType type, boolean nullable, boolean primaryKey)`, `DbForeignKey(String fromColumn, String toTable, String toColumn)`. Add `tableByName` helper on `DbModel`.

**Step 4: Green.**

**Step 5: Commit** — `feat(schemagen): add DbModel value types`.

---

### Task A2: `DraftSchema` object graph

**Files:**
- Create: `saiku-core/saiku-service/src/main/java/org/saiku/service/schema/generate/draft/DraftSchema.java`
- Create: `.../draft/DraftCube.java`, `DraftDimension.java`, `DraftHierarchy.java`, `DraftLevel.java`, `DraftMeasure.java`, `DraftJoin.java`, `Provenance.java`
- Test: `.../draft/DraftSchemaTest.java`

**Step 1: Failing test** — build a small cube with one dimension + one measure; assert navigation (`cube.dimensions()`, `dimension.hierarchies()`), and that every element carries a non-null `Provenance`.

**Step 2: Run → FAIL.**

**Step 3: Implement** — mutable builder-style classes (not records — we mutate during op replay). `Provenance` is a record: `Provenance(Source source, String ruleId, double confidence)` where `Source` is enum `RULE | LLM | USER`.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): add DraftSchema object graph`.

---

### Task A3: Table classification stage

**Files:**
- Create: `.../infer/TableClassifier.java`
- Create: `.../infer/TableClassification.java` (enum or record with `FACT | DIMENSION | ORPHAN` + reason)
- Test: `.../infer/TableClassifierTest.java`

**Step 1: Failing test** — build a `DbModel` with `orders` (3 FKs out, 1M rows), `customers` (FK'd by orders, 10k rows), `junk` (no relations, 5 rows). Assert classification: orders=FACT, customers=DIMENSION, junk=ORPHAN.

**Step 2: Run → FAIL. Step 3: Implement** — rules: fact = `fkOut >= 2 && rowCount >= 1000`; dimension = table referenced by at least one fact's FK; else orphan. Expose `Map<DbTable, TableClassification>`.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): classify fact vs dimension tables by FK shape`.

---

### Task A4: Dimension builder (flat + one-hop snowflake)

**Files:**
- Create: `.../infer/DimensionBuilder.java`
- Test: `.../infer/DimensionBuilderTest.java`

**Step 1: Failing tests** (two): (a) simple dim table with PK `id` + one string col `name` → single-level dim with key=id, caption source=name; (b) snowflake: `products → product_categories` → two-level dim with a join, deeper snowflakes not recursed.

**Step 2: Run → FAIL. Step 3: Implement** — produce `DraftDimension` per dimension table. `Provenance` = `rule:dim-flat` or `rule:snowflake-1hop`.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): build flat and one-hop snowflake dimensions`.

---

### Task A5: Time dimension builder

**Files:**
- Create: `.../infer/TimeDimensionBuilder.java`
- Test: `.../infer/TimeDimensionBuilderTest.java`

**Step 1: Failing test** — fact with `order_date` and `ship_date` → single shared Time dimension emitted once with Y/Q/M/D levels, role-played twice on the cube.

**Step 2: Run → FAIL. Step 3: Implement** — detect `DATE`/`TIMESTAMP` columns on fact tables. Emit one `DraftDimension` of type `TimeDimension`. Role-play via `DraftCube.addDimensionUsage(name=columnName, source=timeDim)`.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): role-played time dimensions with Y/Q/M/D`.

---

### Task A6: Measure builder

**Files:**
- Create: `.../infer/MeasureBuilder.java`
- Test: `.../infer/MeasureBuilderTest.java`

**Step 1: Failing test** — fact with `amount` (numeric, non-FK, non-date), `customer_id` (FK), `order_date` (date) → one `sum(amount)` measure plus implicit `[Measures].[Fact Count]` using `count(*)`.

**Step 2: Run → FAIL. Step 3: Implement** — skip columns that are PKs, FKs, dates, or non-numeric. Default aggregator SUM. Always append fact-count measure.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): default SUM measures plus implicit fact count`.

---

### Task A7: `SchemaInferrer` orchestrator

**Files:**
- Create: `.../infer/SchemaInferrer.java`
- Test: `.../infer/SchemaInferrerTest.java`
- Fixture: `src/test/resources/schemagen/fixtures/foodmart-mini.json` (hand-crafted small `DbModel` JSON)

**Step 1: Failing test** — load fixture, run `SchemaInferrer.infer(model)`, assert: 1 cube, 4 dimensions (incl. Time), ≥2 measures, every element has `Source.RULE` provenance.

**Step 2: Run → FAIL. Step 3: Implement** — wire `TableClassifier` → per-fact: `DimensionBuilder` for each linked dim, `TimeDimensionBuilder`, `MeasureBuilder`. Return `DraftSchema`.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): SchemaInferrer pipeline orchestrator`.

---

### Task A8: Golden-file coverage

**Files:**
- Fixtures: `classic-star.json`, `snowflake.json`, `multi-fact.json`, `wide-flat.json`, `ambiguous-fact.json` under `fixtures/`.
- Expected outputs: same names with `.expected.json` suffix.
- Test: `.../infer/SchemaInferrerGoldenTest.java` — parameterised over the five fixtures.

**Step 1: Failing test** — load each fixture, infer, serialise draft to JSON, compare against `.expected.json`. Start with all five tests failing on missing expected files.

**Step 2: Run → FAIL (assertion or missing file). Step 3: Record expected** — after manually verifying the output looks correct, write the `.expected.json` files from the actual output for each. Adjust inferrer rules if any fixture's output looks wrong; re-record only after the rule is fixed.

**Step 4: Green. Step 5: Commit** — `test(schemagen): golden fixtures for inferrer`.

---

### Task A9: `MondrianSchemaWriter`

**Files:**
- Create: `.../writer/MondrianSchemaWriter.java`
- Test: `.../writer/MondrianSchemaWriterTest.java`

**Step 1: Failing test** — take a minimal `DraftSchema`, write to XML, parse back through Mondrian's own schema parser without error. Assert the cube + one measure round-trip.

**Step 2: Run → FAIL. Step 3: Implement** — use Mondrian's `mondrian.olap.MondrianDef` classes to build the schema object graph, then `toXML()` via its writer. Do not string-template.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): Mondrian XML writer via object model`.

---

### Task A10: `JdbcIntrospector`

**Files:**
- Create: `.../introspect/JdbcIntrospector.java`
- Test: `.../introspect/JdbcIntrospectorTest.java`

**Step 1: Failing test** — use an in-memory H2 database seeded with a small fact+dim schema; run introspector against the connection; assert the resulting `DbModel` matches the seeded shape (tables, columns, FKs, PK flags).

**Step 2: Run → FAIL. Step 3: Implement** — `DatabaseMetaData#getTables`, `#getColumns`, `#getPrimaryKeys`, `#getImportedKeys`. Row-count: try vendor stats view first (dialect-dispatched); fall back to `SELECT COUNT(*)` guarded by `tableSizeThreshold` (skip if unknown-size and over threshold).

**Step 4: Green. Step 5: Commit** — `feat(schemagen): JDBC introspector via DatabaseMetaData`.

---

## Phase B — LLM enrichment

### Task B1: `SuggestionSet` ops model

**Files:**
- Create: `.../enrich/ops/SuggestionOp.java` (sealed interface)
- Create: `.../enrich/ops/RenameOp.java`, `HierarchyOp.java`, `AggregatorOp.java`, `DegenerateDimOp.java`, `IgnoreOp.java`
- Create: `.../enrich/SuggestionSet.java`
- Test: `.../enrich/SuggestionSetTest.java`

**Step 1: Failing test** — build a `SuggestionSet` with one of each op type; serialise via Jackson to JSON and round-trip; assert type discriminator preserved.

**Step 2: Run → FAIL. Step 3: Implement** — sealed interface with `@JsonTypeInfo` for polymorphic serialisation. Each op records `targetPath` (string like `cubes/Sales/measures/Amount`), old value, new value, confidence, rationale.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): suggestion op model`.

---

### Task B2: `LlmProvider` SPI + `NoopProvider`

**Files:**
- Create: `.../enrich/provider/LlmProvider.java` (interface)
- Create: `.../enrich/provider/EnrichRequest.java`, `EnrichResponse.java`
- Create: `.../enrich/provider/NoopProvider.java`
- Test: `.../enrich/provider/NoopProviderTest.java`

**Step 1: Failing test** — feed a draft with `order_date` (rule-provenance dim named "OrderDate") and a dim column named `country_code` → `NoopProvider` returns a `RenameOp` with caption "Order Date" and flags the geo column for a `HierarchyOp` starter.

**Step 2: Run → FAIL. Step 3: Implement** — name-pattern rules: camel-split + title-case for captions; geo column detection via keyword list; `avg_*`/`*_rate` → `AggregatorOp(AVG)`.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): LlmProvider SPI with offline NoopProvider`.

---

### Task B3: `LlmEnricher`

**Files:**
- Create: `.../enrich/LlmEnricher.java`
- Create: `.../enrich/PiiFilter.java`
- Test: `.../enrich/LlmEnricherTest.java`, `.../enrich/PiiFilterTest.java`

**Step 1: Failing tests** — (a) `PiiFilter` removes sample values for columns named `ssn`, `password`, `email_address` but keeps them for `customer_name`; (b) `LlmEnricher` given a draft + `NoopProvider` returns a non-empty `SuggestionSet`, chunked per cube, with no PII-column samples in the outgoing request.

**Step 2: Run → FAIL. Step 3: Implement** — build `EnrichRequest` per cube (draft JSON + filtered column samples), call provider, accumulate `SuggestionSet`. Retry on malformed response up to N times, then fall back to `NoopProvider` for that cube and mark the response as degraded.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): LLM enricher with PII filter and provider fallback`.

---

### Task B4: Anthropic provider adapter (opt-in)

**Files:**
- Create: `.../enrich/provider/AnthropicProvider.java`
- Test: `.../enrich/provider/AnthropicProviderContractTest.java` (JUnit `@EnabledIfEnvironmentVariable(named="ANTHROPIC_API_KEY")`)

**Step 1: Failing test** — contract test: call `enrich` on a tiny draft, assert response parses into a valid `SuggestionSet` (not asserting *content*, just shape).

**Step 2: Run → FAIL (or SKIP without key). Step 3: Implement** — HTTP client (JDK 17 `HttpClient`), tool-use / JSON-schema-constrained output targeting `SuggestionSet` schema, low temperature, seed where supported.

**Step 4: Green (with key) / Skip (without). Step 5: Commit** — `feat(schemagen): Anthropic provider adapter`.

---

### Task B5: Op applier

**Files:**
- Create: `.../apply/OpApplier.java`
- Test: `.../apply/OpApplierTest.java`

**Step 1: Failing tests** — applying a `RenameOp` on `cubes/Sales/measures/Amount` mutates only the caption, not the column mapping; provenance changes to `Source.USER`. `AggregatorOp` changes aggregator and provenance. `IgnoreOp` removes the target. Unknown path → informative exception.

**Step 2: Run → FAIL. Step 3: Implement** — path resolver walks `DraftSchema`, pattern-matches on op type.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): op applier for reviewable suggestions`.

---

## Phase C — REST session layer

### Task C1: Session store

**Files:**
- Create: `saiku-core/saiku-service/src/main/java/org/saiku/service/schema/generate/session/SchemaGenSession.java`
- Create: `.../session/SchemaGenSessionStore.java` (in-memory, `ConcurrentHashMap`, TTL)
- Test: `.../session/SchemaGenSessionStoreTest.java`

**Step 1: Failing test** — create, retrieve, expire. Op log append + replay produces the same draft as direct mutation.

**Step 2: Run → FAIL. Step 3: Implement** — session holds: id, data-source id, current draft, suggestion set, op log (`List<SuggestionOp>` of applied ops + manual edits), stage enum, createdAt. TTL default 30 minutes, configurable.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): in-memory session store with op log`.

---

### Task C2: Async pipeline runner

**Files:**
- Create: `.../session/SchemaGenOrchestrator.java`
- Test: `.../session/SchemaGenOrchestratorTest.java`

**Step 1: Failing test** — start a run against an H2 test DB + `NoopProvider`; poll status; assert stages transition `introspecting → inferring → enriching → ready` and final draft matches the A10+A7 pipeline output.

**Step 2: Run → FAIL. Step 3: Implement** — use existing Saiku `ExecutorService` (from `saiku-service/async` package — check for a reusable one, else a bounded pool). Runner updates session stage as it progresses. Enricher streams per-cube; status reports per-cube completion count.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): async orchestrator with staged status`.

---

### Task C3: REST resource

**Files:**
- Create: `saiku-core/saiku-web/src/main/java/org/saiku/web/rest/resources/schemagen/SchemaGeneratorResource.java`
- Create: DTOs under same package: `StartResponse`, `StatusResponse`, `DraftView`, `SuggestionView`, `OpRequest`.
- Test: `saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/schemagen/SchemaGeneratorResourceTest.java` (Jersey test framework)

**Step 1: Failing test** — end-to-end through Jersey: POST `/start` → 202 + session id; GET `/status` → stages; GET `/draft` → tree JSON; POST `/ops` → updated draft; POST `/save` → 204 + schema in repository.

**Step 2: Run → FAIL. Step 3: Implement** — wire to orchestrator + session store + `MondrianSchemaWriter` + existing `DatasourceService` for credentials, `IRepositoryManager` for writing XML + sidecar.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): REST resource for schema generation sessions`.

---

### Task C4: Wire into JAX-RS / Spring config

**Files:**
- Modify: whichever file registers Jersey resources (grep `AdminResource` bindings) — add `SchemaGeneratorResource`.
- Modify: Spring XML or Java config registering service beans — add `SchemaGenOrchestrator`, `SchemaGenSessionStore`, `LlmEnricher`, `NoopProvider` (default provider bean).
- Test: minimal bean-loading smoke test.

**Step 1: Failing test** — context loads with the new beans.

**Step 2: Run → FAIL. Step 3: Implement wiring. Step 4: Green. Step 5: Commit** — `feat(schemagen): wire resource and services into application context`.

---

## Phase D — Two-pane UI

### Task D1: API client

**Files:**
- Create: `saiku-ui/src/lib/api/schemaGen.ts`
- Test: `saiku-ui/src/lib/api/schemaGen.test.ts` (Vitest)

**Step 1: Failing test** — mock fetch; `start(dataSourceId)` POSTs and returns session id; `pollStatus`, `fetchDraft`, `applyOp`, `save` hit correct endpoints.

**Step 2: Run → FAIL. Step 3: Implement** — thin typed client using existing fetch helper.

**Step 4: Green. Step 5: Commit** — `feat(ui): schema-generator API client`.

---

### Task D2: Session store (Svelte)

**Files:**
- Create: `saiku-ui/src/lib/stores/schemaGen.svelte.ts`
- Test: `saiku-ui/src/lib/stores/schemaGen.svelte.test.ts`

**Step 1: Failing test** — store exposes `draft`, `suggestions`, `stage`, `pendingOps`; `applyOp` optimistically updates then reconciles with server response.

**Step 2: Run → FAIL. Step 3: Implement** — Svelte 5 runes (`$state`, `$derived`). Poll status until `ready`, then stop.

**Step 4: Green. Step 5: Commit** — `feat(ui): schema-generator client store`.

---

### Task D3: Schema tree component (left pane)

**Files:**
- Create: `saiku-ui/src/lib/components/schemagen/SchemaTree.svelte`
- Create: `saiku-ui/src/lib/components/schemagen/NodeDrawer.svelte`
- Test: Svelte component tests as the project's convention allows.

**Step 1: Failing test** — render a fixture draft; assert cube/dim/measure nodes present with provenance badges; clicking a node opens the drawer with editable caption/description.

**Step 2: Run → FAIL. Step 3: Implement. Step 4: Green. Step 5: Commit** — `feat(ui): schema tree with provenance badges and node drawer`.

---

### Task D4: Suggestions feed (right pane)

**Files:**
- Create: `saiku-ui/src/lib/components/schemagen/SuggestionsFeed.svelte`
- Create: `.../SuggestionCard.svelte`

**Step 1: Failing test** — render a fixture `SuggestionSet`; assert grouping by op type; Accept button dispatches `applyOp`; Reject removes the card; bulk "Accept all ≥ high confidence" affects only matching cards.

**Step 2: Run → FAIL. Step 3: Implement. Step 4: Green. Step 5: Commit** — `feat(ui): suggestions feed with accept/reject/bulk`.

---

### Task D5: Generator page

**Files:**
- Create: `saiku-ui/src/routes/admin/schema-generator/[dataSourceId]/+page.svelte`
- Create: `.../+page.ts`

**Step 1: Failing test** — page renders Start button for a data source with no schema; clicking Start kicks off the store; during `enriching` shows streaming suggestions; Save button disabled until stage=ready; on save, navigates back to data-source admin with a toast.

**Step 2: Run → FAIL. Step 3: Implement** — compose D3 + D4 + progress header.

**Step 4: Green. Step 5: Commit** — `feat(ui): schema-generator admin page`.

---

### Task D6: Data-source admin entry point

**Files:**
- Modify: existing data-source admin view — add "Generate schema" button, visible only when the data source has no Mondrian schema attached. Link to `/admin/schema-generator/[dataSourceId]`.

**Step 1: Failing test** — button present + correctly gated.

**Step 2: Run → FAIL. Step 3: Implement. Step 4: Green. Step 5: Commit** — `feat(ui): generate-schema entry on data source admin`.

---

## Phase E — Delta reconciliation & save

### Task E1: Sidecar writer + reader

**Files:**
- Modify: `.../writer/MondrianSchemaWriter.java` — on save, also emit `<schemaName>.generated.json` with the `DraftSchema` + op log.
- Create: `.../writer/GeneratedSidecar.java` (value + I/O).
- Test: `.../writer/GeneratedSidecarTest.java`

**Step 1: Failing test** — save draft, read sidecar back, assert equality of the draft portion and op log.

**Step 2: Run → FAIL. Step 3: Implement. Step 4: Green. Step 5: Commit** — `feat(schemagen): persist generation sidecar alongside schema XML`.

---

### Task E2: `DeltaReconciler`

**Files:**
- Create: `.../delta/DeltaReconciler.java`
- Create: `.../delta/DeltaTag.java` (enum `NEW | EXISTING | REMOVED_UPSTREAM`)
- Test: `.../delta/DeltaReconcilerTest.java`

**Step 1: Failing tests** — starting from a baseline sidecar: (a) add a new column → only that element is `NEW`; (b) drop a column → `REMOVED_UPSTREAM`; (c) no change → all `EXISTING`. Identifiers are stable (table/column names), not captions.

**Step 2: Run → FAIL. Step 3: Implement** — walk both trees, key by stable id, tag each element.

**Step 4: Green. Step 5: Commit** — `feat(schemagen): delta reconciler against baseline sidecar`.

---

### Task E3: Orchestrator re-run mode

**Files:**
- Modify: `SchemaGenOrchestrator` — if the data source already has a schema + sidecar, run `DeltaReconciler` after `SchemaInferrer`; restrict `LlmEnricher` input to `NEW` elements only; return a draft that is the existing schema augmented with the new/removed tags.

**Step 1: Failing test** — seed repository with a saved schema + sidecar; introspect against a DB that gained one column; run orchestrator; assert only the new column yields suggestions.

**Step 2: Run → FAIL. Step 3: Implement. Step 4: Green. Step 5: Commit** — `feat(schemagen): delta-aware re-run mode`.

---

### Task E4: UI re-run affordance

**Files:**
- Modify: D6 entry point — when the data source *already has a schema*, label the button "Regenerate / check for drift" instead of "Generate schema".
- Modify: D5 page — in re-run mode, show a "Changes detected" banner summarising counts (N new, M removed).

**Step 1: Failing test** — snapshot/unit test on the conditional label + banner.

**Step 2: Run → FAIL. Step 3: Implement. Step 4: Green. Step 5: Commit** — `feat(ui): regenerate/drift entry point and delta banner`.

---

### Task E5: End-to-end happy path

**Files:**
- Create: `saiku-core/saiku-web/src/test/java/org/saiku/web/rest/resources/schemagen/FoodmartHappyPathIT.java`

**Step 1: Failing test** — boot Jersey + in-memory repo + Foodmart-mini H2; POST /start; poll to ready; accept all suggestions; POST /save; open the saved schema via existing Saiku APIs; run a known MDX query (`SELECT [Measures].[Fact Count] ON 0 FROM [Sales]`); assert a numeric result.

**Step 2: Run → FAIL. Step 3: Iterate** until pipeline produces a queryable schema. Fix bugs as they surface — they will.

**Step 4: Green. Step 5: Commit** — `test(schemagen): end-to-end Foodmart happy path`.

---

## Out of scope for this plan

- Multi-hop snowflake (>1 level) deterministic handling. Deferred to LLM suggestions only.
- OpenAI / Ollama / Bedrock provider adapters — same shape as Anthropic, add as follow-ups.
- Learning per-project preferences from user rejections.
- Schema-level ACL/visibility suggestions.
- The "fresh wizard" entry point (design §Entry points option 2) — ship after option 1 lands.

---

## Risk register

- **Mondrian object model coverage** — `MondrianSchemaWriter` (A9) depends on `MondrianDef` classes being able to express everything we emit. If we hit gaps, fall back to string templating *only for the affected element type* and write a targeted test.
- **Row-count estimation dialects** — vendor stats views differ. A10 should ship with the generic `COUNT(*)` fallback always working; dialect-specific optimisations can be added incrementally.
- **LLM output conformance** — B3's retry + `NoopProvider` fallback is the safety net. Contract test (B4) must cover the "valid shape, unhelpful content" case.
- **UI component-test tooling** — if Svelte 5 component tests aren't set up, D3/D4 tests may need Playwright instead of Vitest. Check existing UI test setup before starting D3.
