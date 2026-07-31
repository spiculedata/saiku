# Cube/Schema Designer migration into OSS Saiku — Phase 1 plan

**Status:** draft for review · **Branch:** `feature/cube-designer` (worktree) · **Date:** 2026-07-31

## Goal

Open-source the visual **cube/schema designer** (the SvelteKit canvas + inspector +
workbench, ~25k LOC) that currently lives in the closed `saiku-cloud` dashboard, by
moving it into `saiku-ui`, wiring it into the **admin panel**, and publishing it as a
reusable npm package that `saiku-cloud` consumes back — **no code duplication**.

Prep already done in `saiku-cloud` (Phase 0, shipped): the designer's backend calls were
refactored behind an injected `CubeDesignerBackend` (+ optional `CubeDesignerAI`) resolved
from Svelte context, so each host supplies its own adapter. The components hard-code no
backend URL. `SchemaCanvasStore`'s behaviour is now locked by tests (72 store tests,
saiku-cloud #1180).

## The headline finding — OSS already has a cube designer

`saiku-ui` ships a **server-driven schema generator** that overlaps the designer:

- Client: `src/lib/api/schemaGen.ts` (base `/rest/saiku/admin/schema-generator`)
- Route: `src/routes/admin/schema-generator/[dataSourceId]/+page.svelte`
- Backend: `SchemaGeneratorResource.java` + a `SuggestionOp` model
  (rename / hierarchy / aggregator / degenerateDim / ignore) applied via `POST /{sessionId}/ops`,
  persisted through `MondrianSchemaWriter`.

So the migration is a **reconciliation**, not greenfield.

**Decision D1 — RESOLVED: replace.** The old server-driven schema-generator is retired; the Svelte
canvas designer becomes *the* OSS cube designer. Nuance to honour: "remove the schema-generator" means
retiring its **UI + SuggestionOp orchestration** (`/admin/schema-generator/[dataSourceId]` route,
`src/lib/components/schemagen/*`, `schemaGen.ts`, the `SchemaGeneratorResource` session/ops/suggestions
surface). Its **low-level building blocks stay and get repurposed** behind the new thin endpoints — the
new designer still needs introspection (`JdbcIntrospector`) and a Mondrian writer/save path
(`MondrianSchemaWriter`). So we delete the orchestration + UI, keep the primitives.

## Stack + primitive parity (the easy part)

`saiku-ui` is the same stack (SvelteKit 2 + Svelte 5 + Tailwind v4 + `bits-ui` + `tailwind-variants`).
Primitive reconciliation for the port:

| Designer needs | saiku-ui | Action |
|---|---|---|
| `components/ui/button.svelte` | ✅ identical path + `tailwind-variants` | none |
| `design-system/SortableColumnHeader` | ✅ identical | verify prop shape |
| `design-system/FeedbackBanner` | ✅ identical | verify tone vocab |
| `design-system/primitives/Popover` | ❌ absent (only `Tooltip`) | **wrap `bits-ui` Popover** (already a dep) like `components/ui/tooltip.svelte` |
| `@lucide/svelte` | ❌ uses older `lucide-svelte ^1` | **rename import** across the designer (icon names match) |

## Publish recipe (mirror `embed-npm`)

saiku-ui already publishes sub-packages from its `dist/`:
- `vite.config.embed.ts` — standalone lib-mode build, `emptyOutDir:false`, unique filename.
- `scripts/stage-embed-npm.mjs` — copies bundle + rewrites a committed `package.template.json`
  (name `@concepttocloud/saiku-embed`), version tracked from `saiku-ui/package.json`, generated
  `package.json` is git-ignored.
- `.github/workflows/release.yml` job `npm-embed` → **published to public npm (npmjs.org)**, not
  GitHub Packages (correction to the earlier assumption — only the Java jars go to GH Packages).

**Recipe for `@concepttocloud/saiku-cube-designer`:** copy `vite.config.embed.ts` (ESM lib, drop
`customElement`), add `cube-designer-npm/package.template.json` + stage script, chain a resilient
`build:cube-designer*` into `build`, add a release.yml publish job. Must not clobber
`dist/{index.html,saiku-embed.js}` (unique output filename; `emptyOutDir:false`).

## Backend adapter mapping (the hard part) — Saiku REST → `CubeDesignerBackend`

| Adapter method | OSS backing | Status |
|---|---|---|
| `loadSchema` | `repository.ts` (`/api/repository/resource`) + `adminSchemas` + schemagen save | ✅ covered |
| `tryQuery` | `query.ts` `POST /api/query/execute` (ThinQuery) | ✅ covered |
| `profileConnection` (raw tables/columns) | `JdbcIntrospector` exists but only invoked internally by schemagen `start` — **no standalone REST** | ⚠️ **gap** — either drive via schemagen `start`→`draft`, or add a small read-only `/introspect` endpoint |
| `sample` (preview rows for an arbitrary JDBC table) | only SQL-preview + OSSIE row-detail exist | ⚠️ **gap** — add a thin `SELECT … LIMIT n` endpoint (or reuse the introspector path) |
| `convertSchema` (M3→M4) | no converter class in `saiku-core`; but the **Mondrian-4 fork (`pentaho:mondrian:4.8.1.x`, `RolapSchemaUpgrader`) is already a Saiku dependency** — this is what saiku-cloud's gateway already proxies to | ⚠️ **gap = a thin REST wrapper** around the existing upgrader, not a from-scratch converter |

Net backend work: **3 small REST endpoints** (introspect, table-sample, m3→m4-convert-wrapper), each
wrapping capability that already exists server-side.

## DimSum (the AI) — injected capability

**Decision D2 — RESOLVED: build OSS schema-authoring AI now.**

Key architecture point: the designer's DimSum **tool executors already run client-side**
(`dimsum-tools.ts` mutates `SchemaCanvasStore` via `add_table` / `add_join` / `create_dimension` /
`add_measure` / …). The server's only job is one **agent turn**: own the system prompt + schema-authoring
tool schemas, call the LLM, and return the tool-use blocks for the client to execute — exactly what the
Cloud gateway's `/me/inference/dimsum` does. So the OSS build is a **new Saiku endpoint** (e.g.
`POST /rest/saiku/api/ai/cube-designer/turn`) that:

- reuses `AiAskService`'s LLM plumbing (provider call, streaming) + the `AgentSpace`/`AgentSkill`
  persona/skills infra as-is,
- carries a **schema-authoring** system prompt + the designer's client-side canvas tool schemas
  (NOT the query-oriented `query/insight/view_change/refusal` set, and NOT server `SuggestionOp`s),
- returns `{ content: AnthropicBlock[] }` for the client's existing agent loop.

The OSS `CubeDesignerAI` adapter's `fetchImpl` points DimSum at this endpoint; Cloud keeps its
gateway-backed one. This reuses Saiku's AI transport wholesale — only the tool set + prompt differ.

## Admin wiring

Admin is a tabbed SPA (`/admin/+page.svelte`), except the schema-generator which is a **dedicated
dynamic route**. Follow that precedent: `src/routes/admin/cube-designer/[dataSourceId]/+page.svelte`
(`prerender=false`), launched from a per-datasource action in `DatasourcesAdmin.svelte`, returning to
`/admin?tab=datasources`. The route provides the OSS `CubeDesignerBackend` via `setCubeDesignerBackend`.

## Phased steps

- **1a — package skeleton** (saiku-ui): add the build + stage + template + release job for
  `@concepttocloud/saiku-cube-designer` producing an empty/placeholder export. Prove publish plumbing
  before moving code.
- **1b — move the components**: copy `schema-canvas/**` into `saiku-ui/src/lib/cube-designer/`;
  reconcile primitives (Popover wrap, lucide rename, `$lib` paths). svelte-check green; port the store
  tests (they travel unchanged).
- **1c — OSS backend endpoints**: the 3 REST wrappers over existing building blocks — introspect
  (`JdbcIntrospector`), table-sample, m3→m4-convert (`RolapSchemaUpgrader`). Integration-test each.
- **1d — OSS adapter + admin route**: `src/lib/cube-designer/oss-backend.ts` mapping the interface to
  the new + existing endpoints; the dedicated `/admin/cube-designer/[dataSourceId]` route (mirrors
  schema-generator; launched from a `DatasourcesAdmin` action; `prerender=false`).
- **1e — OSS DimSum endpoint** (D2): `POST /rest/saiku/api/ai/cube-designer/turn` — schema-authoring
  agent turn over `AiAskService`, returning tool-use blocks the client executes; wire the OSS
  `CubeDesignerAI` adapter to it.
- **1f — retire the old schema-generator** (D1): remove the `/admin/schema-generator` route,
  `components/schemagen/*`, `schemaGen.ts`, and the `SchemaGeneratorResource` orchestration
  (session/ops/suggestions), keeping `JdbcIntrospector` + `MondrianSchemaWriter` (now used by 1c).
  Update `DatasourcesAdmin` action → the new designer.
- **1g — publish** the package from saiku-ui's release flow.
- **1h — saiku-cloud consumes** the published package (replace its local `schema-canvas/` dir with the
  dep + its gateway adapter); delete the local copy. All saiku-cloud prototype + live smokes stay green.

**Note on the other developer:** 1f deletes a recently-built OSS surface (schema-generator). Coordinate
before merging — the removal PR should be explicit and land after the replacement is in place, so
`development` is never left without a designer.

## Rollback

Each phase is independently revertable. The package is additive to saiku-ui until 1f; saiku-cloud keeps
its local copy until the published package is proven, so the consume step (1f) is a swap that can be
reverted by restoring the local dir. No engine/data-plane changes.

## Decisions (resolved 2026-07-31)

- **D1 — Replace.** Retire the old server-driven schema-generator (UI + SuggestionOp orchestration);
  the Svelte canvas designer becomes the OSS cube designer. Keep + repurpose `JdbcIntrospector` +
  `MondrianSchemaWriter`.
- **D2 — Build OSS AI now.** New `.../ai/cube-designer/turn` endpoint over `AiAskService`, returning
  tool-use blocks for the client's existing agent loop (client-side canvas tools; not SuggestionOps).
- **D3 — `@concepttocloud/saiku-cube-designer`** on public npm (mirrors `embed`).
- **D4 — Dedicated route**, mirroring the schema-generator.
