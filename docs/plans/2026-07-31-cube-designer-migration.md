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

So the migration is a **reconciliation**, not greenfield. **Decision D1 (below) is which model wins.**

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

Per the agreed model, DimSum is an **optional injected capability**. saiku-ui's "DimSum" is
NL-to-query (fixed 4-tool set: query / insight / view_change / refusal, cube-locked) — the **wrong
shape** for schema authoring. The reusable parts are the transport (`ask/chain/stream` SSE,
`aiAsk.ts` parser) and the `AgentSpace`/`AgentSkill` persona plumbing; the tools would need to be a
new schema-authoring set emitting `SuggestionOp`s against a schema-gen session.

**Recommendation for OSS v1:** ship the designer's AI **slot empty** in OSS (manual designer only);
Cloud keeps its gateway-backed DimSum. Building an OSS schema-authoring agent is a separate follow-up,
not a blocker for the migration.

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
- **1c — OSS backend endpoints**: the 3 REST wrappers (introspect, sample, m3→m4). Integration-test each.
- **1d — OSS adapter + admin route**: `src/lib/cube-designer/oss-backend.ts` mapping the interface to
  the new + existing endpoints; the `/admin/cube-designer/[dataSourceId]` route wiring it.
- **1e — publish** the package from saiku-ui's release flow.
- **1f — saiku-cloud consumes** the published package (replace its local `schema-canvas/` dir with the
  dep + its gateway adapter); delete the local copy. All saiku-cloud prototype + live smokes stay green.

## Rollback

Each phase is independently revertable. The package is additive to saiku-ui until 1f; saiku-cloud keeps
its local copy until the published package is proven, so the consume step (1f) is a swap that can be
reverted by restoring the local dir. No engine/data-plane changes.

## Open decisions (need your call before 1b)

- **D1 — Two designers or one?** Ship the Svelte canvas designer **alongside** the existing
  schema-generator (fastest; two overlapping surfaces for a while), or **converge** the Svelte UI onto
  the schema-generator's `SuggestionOp` backend (less duplication, much more reconciliation)?
  *Recommendation: alongside for v1, converge later.*
- **D2 — DimSum in OSS v1?** Empty AI slot (recommended) vs build the schema-authoring agent now.
- **D3 — Package name/registry.** `@concepttocloud/saiku-cube-designer` on public npm (mirrors embed)?
- **D4 — Admin surface.** Dedicated route (recommended, mirrors schema-generator) vs a new admin SPA tab.
