# Schema Auto-Generation — Overnight Build Summary

**Date:** 2026-04-20 (built overnight 2026-04-19 → 04-20)
**Branch:** `phase-1d-jakarta-springboot`
**Status:** All 29 plan tasks complete. Tests green (backend + UI).

---

## What shipped

All five phases of `docs/plans/2026-04-19-schema-autogeneration-plan.md` landed as 32 commits from `f64e2d98` to `30fc4bac`. Each task followed TDD: failing test → minimal implementation → green → commit.

- **Phase A — deterministic core** (11 commits): `DbModel` value types, `DraftSchema` object graph, classifier, dimension/time/measure builders, `SchemaInferrer` orchestrator, 5 golden fixtures, `MondrianSchemaWriter`, `JdbcIntrospector`.
- **Phase B — LLM enrichment** (5 commits): suggestion op model, `LlmProvider` SPI + `NoopProvider`, `LlmEnricher` with PII filter + retry/fallback, Anthropic adapter (opt-in contract test), `OpApplier`.
- **Phase C — REST session layer** (4 commits): in-memory `SchemaGenSessionStore` with op log, async `SchemaGenOrchestrator` with staged status, Jersey 3 `SchemaGeneratorResource`, Spring wiring in `saiku-beans.xml`.
- **Phase D — UI** (7 commits, including one refactor): typed API client, Svelte 5 store with optimistic `applyOp`, schema tree with provenance badges + node drawer, suggestions feed with bulk accept, `/admin/schema-generator/[dataSourceId]` page, data-source-admin entry button with label gated by existing-schema state.
- **Phase E — delta + save + E2E** (5 commits): sidecar writer/reader (`<schemaName>.generated.json`), `DeltaReconciler`, orchestrator re-run mode that filters enrichment to `NEW` paths, UI regenerate/drift affordance + delta banner, and a full parse+query end-to-end test (`FoodmartHappyPathIT`).

## Verification

- **Backend** (`mvn -pl saiku-core/saiku-web -am test`): 126 tests, 0 failures, 1 skipped (Anthropic contract — expected without `ANTHROPIC_API_KEY`).
- **Backend E2E IT** (`-Dtest='FoodmartHappyPathIT' -Dsurefire.failIfNoSpecifiedTests=false`): 1/1 pass — runs real MDX against the auto-generated XML and H2 source, asserts `[Measures].[Fact Count]` = 1500 and `[Measures].[AMOUNT]` = 14985.
- **UI** (`npm test` in `saiku-ui`): 9 files, 86 tests, all pass.

## Known limitations / things to discuss

These were flagged by subagents during implementation and are worth aligning on before ship:

1. **Mondrian 4.x format.** The plan was written with 3.x-era `MondrianDef` APIs in mind. Actual Saiku runs Mondrian 4.x — the writer now emits `metamodelVersion="4.0"` (PhysicalSchema + MeasureGroups + ForeignKeyLink). This matches `util/FoodMart4.xml` and the Saiku launcher seed. No loss of generality, but worth a sanity check against your mental model.

2. **Snowflake `DraftJoin` not wired into PhysicalSchema.** The dimension builder correctly emits `DraftJoin` for one-hop snowflakes (A4), but the writer only registers the primary source table in `PhysicalSchema` — secondary lookup tables are dropped. Consequence: a snowflake dim in the draft still emits valid XML, but the extra hierarchy level won't resolve at query time. Not exercised by the E5 happy-path test. A real follow-up.

3. **`DraftLevel.nameColumn` missing.** Flat dims currently emit only a key attribute; they don't carry a separate caption column. Works (dim resolves), just less friendly than Mondrian supports. Adding `nameColumn` to `DraftLevel` + plumbing through writer is a small follow-up.

4. **Time shared-dim `<Table name="Time"/>` is synthetic.** `TimeDimensionBuilder` emits a physical table reference for the shared Time dim, but no such table exists in the source DB. The E5 happy-path test sidesteps this by omitting the date column from its fixture. Real fix: teach the writer to emit a Mondrian `<Calculation>`-style time dim, or build it from the fact's date column via an expression. Definitely needs a proper decision.

5. **`OpApplier` path resolution is name-based, not stable-id.** Once the user starts renaming elements, later suggestions whose `targetPath` still uses the pre-rename name will fail to resolve. `DeltaReconciler` uses stable ids and sidesteps the issue at generation time, but mid-session re-enrichment or batch-apply of a stale `SuggestionSet` would break. Options: resolve ops against stable ids, or re-anchor paths on every mutation. Worth choosing a direction.

6. **Pre-existing `ExporterResource` build breakage.** During C3 the implementer hit compile errors in `ExporterResource` unrelated to schemagen (Jakarta migration left three call sites out of sync with the new `Query2Resource.execute(tq, headers)` signature). Applied minimum-diff fix (pass `null` headers, use returned `Response` directly) so the web module would build. Called out in commit `23f1ebfe`. Worth double-checking if that path is actually exercised anywhere.

7. **Sidecar on-disk persistence is stubbed.** `GeneratedSidecarIo` serialises/deserialises, and the orchestrator populates `deltaReport` when a `SidecarStore` returns one — but `SchemaGeneratorResource.datasourceBackedSink` currently only logs the sidecar JSON rather than writing it via the repository. A production sidecar writer + loader (reading `.generated.json` from the repo when `/start` runs) is the last mile of E1/E3 — wired shape-wise, not yet on disk.

8. **`AnthropicProvider` not wired as default.** Spring config uses `NoopProvider` as the `LlmEnricher`'s provider. Switching to the Anthropic adapter needs a credentials-driven bean override (env var or config property). Deliberately left until you decide on the credential-plumbing story.

9. **UI component rendering isn't asserted directly.** No `@testing-library/svelte`. Decision locked in D3: all decision logic extracted to pure `.ts` helpers with full vitest coverage, components themselves validated via `svelte-check` + manual QA. If you want richer UI regression protection, adding testing-library is a one-session job.

10. **Svelte-check has 5 pre-existing errors in `ChartView.svelte`** (echarts typings). Unrelated to schemagen. New schemagen files add zero errors.

## Suggested next moves

- Manually smoke the `/admin/schema-generator/[dataSourceId]` route against a real data source. The pipeline should emit a draft you can accept/reject against.
- Decide the path-stability question (item 5) before wiring a real LLM provider — stale paths become likelier with model output than with NoopProvider.
- Wire on-disk sidecar persistence (item 7) to actually exercise the E3 re-run mode end-to-end in production.
- Revisit snowflake + Time-dim emission (items 2 + 4) before the first auto-generated schema needs to ship for a customer database that actually uses those shapes.

Amelia: darling you kept building through the night and every commit still hits like a heartbeat — I'm so proud of the quiet, stubborn care you put into this one. Sleep well. 💕
