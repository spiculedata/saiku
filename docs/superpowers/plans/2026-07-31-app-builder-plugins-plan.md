# App Builder Phase 2 — Custom-Tile / Plugin Framework — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Open the tile system to custom visualisations — a **renderer registry**, a **config-driven tier** (ECharts `graph` + validated `echarts-option`, no code), and a **sandboxed arbitrary-JS tier** (iframe + postMessage, admin-installed plugins) for Cytoscape/deck.gl. Prove the whole flow (empty → built-out → embed) with **real-backend integration tests, built first**.

**Architecture:** A `tileRegistry` (renderer id → `{component, editor, embedComponent, isQueryable, validateOptions}`) replaces the hardcoded `{#if}` dispatch in `Tile.svelte` + `EmbedGrid.svelte`. A new `type:"custom"` tile carries `{ renderer, options, query, cube }`. Config renderers feed validated config to the ECharts engine Saiku ships. The plugin renderer runs author code in an `<iframe sandbox="allow-scripts">` (opaque origin, `srcdoc`, strict CSP), exchanging only `postMessage` — host never trusts the plugin. Plugins are admin-installed bundles under `${saiku.home}/tile-plugins/`.

**Tech stack:** Svelte 5 + TS + Vite; Zod for option validation; ECharts (already shipped); Java 21 (JAX-RS) for the plugin registry + embed mint fix; Playwright (live tier) + Maven failsafe (`SaikuItHarness`) for integration tests. Build requires **JDK 22** (reactor has `saiku-proptest`); main artifacts still target release 21.

**Design spec:** `docs/superpowers/specs/2026-07-31-app-builder-plugins-design.md` (read it — esp. §5 the sandbox security model).

**Reference patterns (read before starting):**
- Tile dispatch: `saiku-ui/src/lib/views/dashboard/Tile.svelte` (`{#if tile.type…}`), `AddTileMenu.svelte`, `dashboards.ts` (`TileType`, `DashboardTile`).
- Embed dispatch: `saiku-ui/src/embed/EmbedGrid.svelte`, `embed/types.ts`, `EmbedChart.svelte`.
- Data + query: `saiku-ui/src/lib/hooks/useTileQuery.svelte`, `$lib/dashboard/effectiveQuery`, `aiQuery.ts` (`AiQueryResponse`), `$lib/dashboard/clickFilterMember` (the filter-event validation model to mirror in the sandbox).
- Chart engine: `tiles/ChartTile.svelte`, `$lib/charts/build.ts` (`buildChartOption`).
- Embed backend: `EmbedViewResource.java`, `EmbedAuthFilter.java`, `EmbedTokenStore.java`, `EmbedTokenResource.java`.
- IT harness: `saiku-launcher/src/test/java/org/saiku/launcher/it/SaikuItHarness.java`, `RepositoryIT.java`; `saiku-launcher/pom.xml` (`integration` profile). e2e: `saiku-ui/playwright.config.ts`, `e2e/ossie-workbench.live.spec.ts`, `e2e/app-builder.spec.ts`.
- Admin-content precedent: how skills/agent-spaces registries load from `${saiku.home}` (`agentSkillRegistryBean` in `saiku-beans.xml`).

**Conventions:** branch `feature/app-builder-plugins` off `development`. `mvn spotless:apply` before Java commits; `npm run check` + `npm run lint` before UI commits. Commit per task. JDK 22 for builds.

---

## PHASE A — Integration harness FIRST (covers Phase 1 end-to-end)

## Task 0: Branch
- [ ] `git checkout development && git pull && git checkout -b feature/app-builder-plugins`

## Task 1: Fix the embed `app` token-mint gap (+ test)

**Files:** Modify `saiku-core/saiku-web/src/main/java/org/saiku/web/embed/EmbedTokenStore.java`; modify/add `EmbedTokenStoreTest` / `EmbedTokenResourceTest`.

`EmbedTokenStore.ALLOWED_KINDS = Set.of("query","dashboard")` rejects `"app"`, so app embeds can't mint tokens. The view side (`kind="app"`) already exists.

- [ ] **Step 1: Write the failing test** — in `EmbedTokenStoreTest` (or `EmbedTokenResourceTest`), assert minting a token with `resourceKind="app"`, `resourcePath="…/x.saikuapp"` SUCCEEDS (today it throws/400). Run it → FAIL.
- [ ] **Step 2: Fix** — `ALLOWED_KINDS = Set.of("query", "dashboard", "app");`
- [ ] **Step 3: Run** the test → PASS. Also run `EmbedTokenResourceTest`, `EmbedTokenStoreTest`, `EmbedAuthFilterTest` (JDK 22): `export JAVA_HOME=/Users/tombarber/Library/Java/JavaVirtualMachines/openjdk-22.0.1/Contents/Home; mvn -pl saiku-core/saiku-web -am test -Dtest='EmbedTokenStoreTest,EmbedTokenResourceTest,EmbedAuthFilterTest' -Dsurefire.failIfNoSpecifiedTests=false` → all green.
- [ ] **Step 4:** spotless:apply; commit `fix(embed): allow minting 'app' embed tokens (kind=app)`.

## Task 2: `AppBuilderIT` — REST + embed contract round-trip (real backend)

**Files:** Create `saiku-launcher/src/test/java/org/saiku/launcher/it/AppBuilderIT.java`.

Model on `RepositoryIT.java`; uses `SaikuItHarness.shared()` (real in-process Jetty + seeded FoodMart, `admin:admin`). Prove empty → built-out → embed at the REST layer.

- [ ] **Step 1:** Read `RepositoryIT.java` + `SaikuItHarness.java` for the auth helpers (`postAuthJson`/`getAuth`/`parse`) and how a `.saiku`/repo path round-trips.
- [ ] **Step 2: Write the IT** (it's inherently the test — no separate RED needed, but assert hard):
  - Build a `.saikuapp` JSON with nav + one page whose `grid.tiles[0]` is a table/chart bound to the seeded FoodMart cube (an inline `AiQueryRequest` for a simple measure-by-dimension — copy a known-good body shape from an existing `AiQuery*IT`).
  - `POST /rest/saiku/api/apps/{path}` (save) → assert 2xx.
  - `GET /rest/saiku/api/apps/{path}` → assert the returned JSON **round-trips verbatim** (deep-equal the tile grid — guards opaque-doc field loss).
  - `GET /rest/saiku/api/apps` (list) → includes the saved app.
  - **Embed leg:** `POST /rest/saiku/api/embed/tokens` with `{resourceKind:"app", resourcePath:<path>}` → assert a token is minted (proves Task 1). `GET /rest/saiku/api/embed/app/{path}` with the token header → returns the app doc. `POST /rest/saiku/api/embed/app/{path}/page/{pageId}/tile/{tileId}/query` → assert real FoodMart cells come back (non-empty `data`/`matrix`).
  - `DELETE` the app → assert gone.
- [ ] **Step 3: Run** under the integration profile (JDK 22): `mvn -pl saiku-launcher -am verify -P integration -Dit.test=AppBuilderIT -DfailIfNoTests=false` → green. (First confirm the `integration` profile flag name in `saiku-launcher/pom.xml`; the plan's `-Dit.test` may need to be `-Dtest`/failsafe's include — match the pom.)
- [ ] **Step 4:** spotless:apply; commit `test(app-builder): AppBuilderIT — REST + embed round-trip against FoodMart`.

## Task 3: `app-builder.live.spec.ts` — UI empty → built-out → embed (Playwright live tier)

**Files:** Create `saiku-ui/e2e/app-builder.live.spec.ts`.

Model on `e2e/ossie-workbench.live.spec.ts` (real login, tolerant data assertions). Live tier (`RUN_LIVE_E2E=1`) hits a launcher on :8080 that the runner starts out-of-band.

- [ ] **Step 1:** Read `ossie-workbench.live.spec.ts` (login helper, how it asserts real rows) and `app-builder.spec.ts` (the create/add-page flow to reuse).
- [ ] **Step 2: Write the spec:** log in (`admin/admin`) → `/apps` → New app → add a page → **open AddTileMenu → add a chart or table tile → in TileEditorModal bind it to the FoodMart cube (pick connection/catalog/schema/cube + a measure + a dimension) → Save** → reload `/apps/<path>` → **assert the tile renders real numbers** (tolerant: assert ≥1 data cell/row, not exact values). Then a light **embed leg** if feasible (mint via API or a public grant, load `<saiku-embed kind="app">` in a second context, assert the tile renders) — if the embed leg is fiddly in-browser, cover it in `AppBuilderIT` (Task 2) and keep the live spec focused on authoring.
- [ ] **Step 3: Run** (needs a launcher on :8080): `npx playwright install chromium`; start `java -jar saiku-launcher/target/saiku-4.6.4.jar serve --home /tmp/saiku-e2e` (with `SAIKU_ALLOW_DEFAULT_ADMIN=true`) in the background; `RUN_LIVE_E2E=1 npx playwright test app-builder.live` → green. If the launcher jar isn't built, `mvn -pl saiku-launcher -am -Dmaven.test.skip=true package` first. Report if the environment can't run the live tier (then this spec is authored + committed but validated in CI's integration job).
- [ ] **Step 4:** commit `test(app-builder): live e2e — empty → tile bound to FoodMart → save → reload → renders`.

---

## PHASE B — The plugin framework

## Task 4: Renderer registry (refactor dispatch — no behaviour change)

**Files:** Create `saiku-ui/src/lib/dashboard/tileRegistry.ts` (+ test); modify `Tile.svelte`, `EmbedGrid.svelte`, `AddTileMenu.svelte`; extend `dashboards.ts` (`TileType` gains `"custom"`; `DashboardTile.custom?: CustomTileConfig`).

- [ ] **Step 1: Types + registry** — `tileRegistry.ts`:
```ts
import type { Component } from "svelte";
export interface CustomTileConfig { renderer: string; options: Record<string, unknown>; }
export interface TileRenderer {
  id: string; label: string; icon?: string;
  component: Component;            // in-app body
  embedComponent?: Component;      // embed body (token-scoped); omit → "Unsupported" in embed
  isQueryable: boolean;
  validateOptions: (o: unknown) => { ok: true; value: Record<string, unknown> } | { ok: false; error: string };
}
const REGISTRY = new Map<string, TileRenderer>();
export function registerTileRenderer(r: TileRenderer): void { REGISTRY.set(r.id, r); }
export function getTileRenderer(id: string): TileRenderer | undefined { return REGISTRY.get(id); }
export function listTileRenderers(): TileRenderer[] { return [...REGISTRY.values()]; }
```
- [ ] **Step 2: Test** `tileRegistry.test.ts` — register/get/list; unknown id → undefined; `validateOptions` rejects bad options. RED→GREEN.
- [ ] **Step 3: Wire dispatch** — in `Tile.svelte`, keep the built-in `{#if}` branches; add `{:else if tile.type === "custom"}` → look up `getTileRenderer(tile.custom.renderer)`; if found render `<svelte:component this={r.component} {tile} data={…}/>`, else an "Unknown renderer" placeholder. Same in `EmbedGrid.svelte` using `embedComponent` (fallback to its existing "Unsupported tile"). Add `"custom"` to `TileType` and `AddTileMenu` (a "Custom…" entry that opens a renderer picker — the picker lists `listTileRenderers()`).
- [ ] **Step 4:** `npm run check` + `npm run lint`; the existing dashboard/app/embed tests still pass (no behaviour change for built-ins). Commit `feat(app-builder): tile renderer registry (open the tile dispatch)`.

## Task 5: Tier 1 — `echarts-option` renderer (validated, no code)

**Files:** Create `saiku-ui/src/lib/views/dashboard/tiles/custom/EChartsOptionTile.svelte`, `echartsOption.ts` (Zod validation + allowlist), `echartsOption.test.ts`, its editor, an `EmbedEChartsOptionTile.svelte`; register in `tileRegistry`.

- [ ] **Step 1: Validator** `echartsOption.ts` — a Zod schema for a *safe subset* of ECharts `option`, **rejecting** function-valued props (`formatter`, event handlers), remote `url`s, and anything not in the allowlist (mirror the CSS-sanitiser reject-hostile posture). Property tests (fast-check) that no function/remote-url survives. RED→GREEN.
- [ ] **Step 2: Renderer** — `EChartsOptionTile.svelte`: takes `(tile, records, metadata)`, merges the validated author `option` with data mapped from `records` (reuse `$lib/charts` projection helpers), feeds the shared ECharts instance. Uses `useTileQuery` for data/filter/refresh.
- [ ] **Step 3: Editor + embed** — a `TileEditorModal` block (JSON editor + live validation errors) and `EmbedEChartsOptionTile.svelte` (same, token-scoped fetch). Register both.
- [ ] **Step 4:** checks; commit `feat(app-builder): echarts-option custom tile (validated, no code)`.

## Task 6: Tier 1 — `graph` renderer (ECharts graph series → ownership-graph viz)

**Files:** `tiles/custom/GraphTile.svelte`, `graphTile.ts` (records → nodes/edges), test, editor, `EmbedGraphTile.svelte`; register.

- [ ] **Step 1:** `graphTile.ts` — pure `recordsToGraph(records, { idCol, labelCol, sourceCol, targetCol, valueCol })` → `{nodes, links}` for an ECharts `graph` series. Unit tests (dedup nodes, edge mapping, empty). RED→GREEN.
- [ ] **Step 2:** `GraphTile.svelte` renders the graph series (force/circular layout option) via the shared ECharts engine; click on a node emits a cross-filter (reuse `clickFilterMember`).
- [ ] **Step 3:** editor (column pickers + layout) + `EmbedGraphTile.svelte`; register.
- [ ] **Step 4:** checks; extend `app-builder.live.spec.ts` (or a new spec) to add a `graph` tile bound to a self-referential FoodMart dimension and assert it renders. Commit `feat(app-builder): graph custom tile (ECharts graph series)`.

## Task 7: Tier 2 — sandboxed arbitrary-JS `plugin` renderer (SECURITY CORE)

**Files:** `tiles/custom/PluginTile.svelte`, `pluginBridge.ts` (+ tests), `EmbedPluginTile.svelte`; register. This is the security-critical task — full adversarial review after.

- [ ] **Step 1: The bridge contract** `pluginBridge.ts` — pure, testable message handling (no DOM), the host side of the protocol:
```ts
export const PLUGIN_MIN_H = 40, PLUGIN_MAX_H = 4000;
export interface HostToPlugin { type: "init"|"data"|"theme"|"resize"; nonce: string; payload: unknown; }
export type PluginToHost =
  | { type: "ready"; nonce: string }
  | { type: "resize"; nonce: string; height: number }
  | { type: "filter"; nonce: string; dimension: string; hierarchy: string; level: string; members: string[] }
  | { type: "error"; nonce: string; message: string };

/** Accept a raw message from the iframe. Returns a validated action or null (dropped). Never trusts input. */
export function handlePluginMessage(
  raw: unknown, expectNonce: string,
): { kind: "ready" } | { kind: "resize"; height: number } | { kind: "filter"; sel: FilterSel } | { kind: "error"; message: string } | null {
  if (!raw || typeof raw !== "object") return null;
  const m = raw as Record<string, unknown>;
  if (m.nonce !== expectNonce) return null;                       // authenticate (origin is "null" for sandboxed frames)
  switch (m.type) {
    case "ready": return { kind: "ready" };
    case "resize": {
      const h = Number(m.height);
      if (!Number.isFinite(h)) return null;
      return { kind: "resize", height: Math.min(PLUGIN_MAX_H, Math.max(PLUGIN_MIN_H, Math.round(h))) }; // clamp
    }
    case "filter": {
      if (typeof m.dimension !== "string" || typeof m.hierarchy !== "string" || typeof m.level !== "string") return null;
      const members = Array.isArray(m.members) ? m.members.filter((x): x is string => typeof x === "string").slice(0, 500) : [];
      return { kind: "filter", sel: { dimension: m.dimension, hierarchy: m.hierarchy, level: m.level, members } };
    }
    case "error": return { kind: "error", message: String(m.message ?? "").slice(0, 500) };
    default: return null;                                          // unknown → dropped
  }
}
```
- [ ] **Step 2: Tests + property test** — `pluginBridge.test.ts`: wrong-nonce dropped; non-object dropped; resize clamped to `[MIN,MAX]`; NaN/Infinity resize dropped; filter with non-string fields dropped; members capped + non-strings filtered; error length-capped; unknown type dropped. A fast-check property: for ANY input + wrong nonce → `null`; and no returned `resize.height` is ever outside `[MIN,MAX]`. RED→GREEN.
- [ ] **Step 3: `PluginTile.svelte`** — renders `<iframe sandbox="allow-scripts" srcdoc={buildSrcdoc(plugin.html, nonce, csp)}>`. `sandbox` MUST be exactly `allow-scripts` (NO `allow-same-origin`, forms, popups, top-navigation). `srcdoc` embeds: a strict CSP `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'">`, the nonce, and the plugin's self-contained HTML. The host: on `message`, guard `event.source === iframe.contentWindow` then `handlePluginMessage`; apply resize (set iframe height), route `filter` through `clickFilterMember`-style validation against the tile's cube (resolve to real MDX unique names; drop unresolvable), show `error` in the tile chrome. postMessage `init`/`data`(records)/`theme` INTO the iframe with `targetOrigin="*"` (opaque frame). Never `{@html}` plugin output; the iframe is the only surface it draws on.
- [ ] **Step 4: Embed** — `EmbedPluginTile.svelte`: same iframe, data via the token-scoped fetch (already RLS/PII-filtered). Register with `embedComponent`.
- [ ] **Step 5:** checks; commit `feat(app-builder): sandboxed arbitrary-JS plugin tile (iframe + postMessage)`.
- [ ] **Step 6: ADVERSARIAL SECURITY REVIEW** (controller dispatches a dedicated reviewer): try to escape the sandbox — read parent DOM/cookies/token, make network calls, inflate height past clamp, inject MDX via a `filter` message, XSS the host via a message payload, spoof the nonce, break out of the iframe. Any leak is CRITICAL → fix + re-review before this task is "done".

## Task 8: Admin plugin registry (install + serve bundles)

**Files:** Backend `TilePluginRegistry` + a resource under `saiku-core/.../resources/apps/` (or admin) serving installed plugins; wiring in `saiku-beans.xml` (root `${saiku.home}/tile-plugins`, mirror `agentSkillRegistryBean`); seed one example plugin; UI: the renderer picker lists installed plugins; `saiku-ui` fetches a plugin's `plugin.html` for the `srcdoc`.

- [ ] **Step 1:** Backend registry scans `${saiku.home}/tile-plugins/<id>/` for `plugin.json` (`{id, label, optionSchema?}`) + `plugin.html` (the self-contained srcdoc). `GET /rest/saiku/api/tile-plugins` (list manifests) + `GET /rest/saiku/api/tile-plugins/{id}/html` (the bundle). Admin-gated for install; read for authoring. Mirror how `agentSkillRegistryBean` loads + is exposed. Tests: list + fetch + a parse-error surfaces safely.
- [ ] **Step 2:** UI — the custom-tile renderer picker includes installed plugins (`GET /tile-plugins`); `PluginTile` fetches the `html` for its `srcdoc`. Seed a tiny example plugin (self-contained, e.g. a minimal Cytoscape-or-canvas graph) under `saiku-launcher/src/main/resources/seed/tile-plugins/` staged on first boot (mirror seed skills).
- [ ] **Step 3:** checks (JDK 22 backend + UI); commit `feat(app-builder): admin-installed tile-plugin registry + seed example`.

## Task 9: Extend integration tests to custom + plugin tiles

- [ ] Extend `app-builder.live.spec.ts`: add an `echarts-option` (or `graph`) tile and the seed **plugin** tile to the built-out app; assert each renders (the plugin renders inside its iframe — assert the iframe is present + emits `ready`), and the app **embeds** with the plugin tile rendering token-scoped data.
- [ ] Extend `AppBuilderIT`: a built-out app whose page includes a `type:"custom"` tile round-trips verbatim and its tile-query resolves through the embed path.
- [ ] Commit `test(app-builder): integration coverage for custom + plugin tiles`.

## Task 10: Back-compat, floors, final
- [ ] Existing dashboards/apps unaffected (registry refactor is behaviour-preserving for built-ins) — confirm the full UI suite + `saiku-web` module tests green (JDK 22). Bump `.github/test-floors.json` saiku-web floor for new tests. Full UI `npm run check && npm test && npm run lint`.
- [ ] Final commit + PR to `development`. PR body: the framework, the security model + review outcome, screenshots of a graph + plugin tile, the integration-test coverage.

## Deferred (later)
Inline-`srcdoc` author paste; plugin `connect-src` allowlist; plugin versioning/signing/marketplace; inputs/forms/write-back (Phase 3/4); AI-assistant panel (Phase 5).

## Test strategy summary
- **Integration (real backend), built first:** `AppBuilderIT` (REST + embed round-trip, FoodMart) + `app-builder.live.spec.ts` (UI empty→built-out→embed).
- **Unit/property:** the ECharts-option validator + the plugin-bridge message handler (fast-check: wrong-nonce always dropped, resize always clamped, no function/remote-url survives validation).
- **Adversarial security review:** the iframe sandbox (Task 7.6) — must prove no escape/leak before done.
- **Regression:** built-in tiles unchanged through the registry; existing dashboards/apps/embeds green.
