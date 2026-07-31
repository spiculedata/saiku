# Saiku App Builder — Phase 2: Custom-Tile / Plugin Framework — Design

**Status:** Draft for review · **Date:** 2026-07-31 · **Owner:** Tom Barber

## 1. Why

Phase 1 shipped the branded multi-page app shell (reusing the fixed 6-tile
palette). Phase 2 opens the tile system so authors can add **custom
visualisations** the palette can't express — the enigma-class need: ownership
graphs (Cytoscape), flow maps (deck.gl), bespoke charts. Chosen scope: the
**full framework** — a config-driven tier *and* a sandboxed arbitrary-JS tier.

**Cross-cutting requirement (this phase):** integration tests that drive the app
**from empty to a built-out example** end-to-end (create → pages → tiles → real
data → save → reload → embed) against a real backend. Built FIRST (§7).

## 2. Findings (from the codebase)

- **Tiles are a closed union** with hardcoded `{#if}` dispatch in `Tile.svelte`
  and a *separate* forked dispatch in `EmbedGrid.svelte`. No plugin API exists.
- **App pages render through `Tile.svelte`** (via the store), so a new tile type
  works in the App Builder for free — but the **embed renderer is forked** and
  needs custom tiles added explicitly (else it degrades to "Unsupported tile").
- **Data contract is clean:** a renderer receives `(tile, AiQueryResponse.records,
  metadata)` — the exact projection charts/tables already get, so a custom tile
  inherits filters/refresh/drill/share.
- **No JS-sandbox primitive exists.** DOMPurify (TextTile) deliberately blocks
  `iframe`/`object`/`script`; the embed shadow-root is style-isolation only.
- **Embed mint gap (Phase-1 loose end):** `EmbedTokenStore.ALLOWED_KINDS =
  {"query","dashboard"}` — `"app"` is not mintable. Fixed here.

## 3. The enabling change — a renderer registry

Replace the hardcoded `{#if}` dispatch (app **and** embed) with a lookup:

```ts
// saiku-ui/src/lib/dashboard/tileRegistry.ts
interface TileRenderer {
  id: string;                    // e.g. "graph", "echarts-option", "plugin"
  label: string;
  component: Component;          // in-app body (Svelte)
  editor?: Component;            // options editor for TileEditorModal
  embedComponent?: Component;    // embed-surface body (token-scoped)
  isQueryable: boolean;          // does it bind a cube/query?
  validateOptions(opts: unknown): Result; // Zod-gated (repo rule)
}
```

- A new tile `type:"custom"` carries `{ renderer: string, options: unknown,
  query?: TileQuery, cube?: CubeRef }` on `DashboardTile` (mirrors the
  `kpi?`/`image?` config pattern).
- `Tile.svelte` and `EmbedGrid.svelte` both dispatch built-in types as today,
  and for `type:"custom"` look up `tileRegistry[tile.custom.renderer]` and render
  its `component` / `embedComponent`. `AddTileMenu` lists registered renderers.
- **The registry is the open surface.** Everything else composes onto it.

## 4. Tier 1 — config-driven renderers (no sandbox)

Built-in renderers that take a **validated config** + the query data, fed to
engines Saiku already ships. Zero arbitrary code.

- **`graph`** — maps records → an ECharts `graph` series (nodes/edges from
  configured columns). Covers ownership-graph-style viz. Author configures
  node/edge/id columns + layout; no raw JS.
- **`echarts-option`** — the author supplies an ECharts `option` object; it is
  **Zod-schema-validated and property-allowlisted** (reject event handlers,
  `formatter` functions, remote `url`s — same reject-hostile posture as the CSS
  sanitiser), then fed to the existing ECharts instance with the tile's records.
- Both reuse `useTileQuery`/`effectiveQuery` → inherit filters, refresh, drill,
  theme, share, and embed for free (they're in the registry with an
  `embedComponent` that uses the token-scoped fetchers).

## 5. Tier 2 — sandboxed arbitrary-JS plugin (the security core)

For true bespoke viz (Cytoscape, deck.gl). A `plugin` renderer that runs the
author's code in a **maximally-isolated iframe**, never trusting it.

### Runtime
- `<iframe sandbox="allow-scripts">` — **NO `allow-same-origin`**, so the frame
  has an **opaque origin**: it cannot read the parent DOM, cookies,
  `localStorage`, the embed token, or same-origin network. No `allow-forms`,
  `allow-popups`, `allow-top-navigation`.
- Content via **`srcdoc`** (a self-contained HTML/JS bundle) — not a remote
  `src`. A strict CSP inside the srcdoc: `default-src 'none'; script-src
  'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; img-src data:`
  — the plugin is **self-contained and network-less** (its viz lib is bundled).
  (A future opt-in `connect-src` allowlist is out of scope here.)

### Host ↔ plugin protocol (postMessage only, host never trusts the plugin)
- A per-mount **random nonce** is embedded in the srcdoc; every message is
  tagged with it. Because a sandboxed frame's `origin` is `"null"`, the host
  authenticates messages by **nonce + `event.source === iframe.contentWindow`**,
  not origin.
- **Host → plugin:** `init` (nonce, theme tokens, size), `data`
  (`AiQueryResponse.records` + metadata — already RLS/PII-filtered upstream),
  `theme`, `resize`.
- **Plugin → host (ALL validated/clamped):**
  - `ready` — plugin mounted.
  - `resize {height}` — clamped to `[MIN, MAX]` px; never trusted verbatim.
  - `filter {dimension, hierarchy, level, members}` — **validated against the
    tile's cube exactly like `clickFilterMember`** (resolve to real MDX unique
    names); an unresolvable/oversize claim is dropped. A plugin can only *narrow*
    within the cube, never inject arbitrary MDX.
  - `error {message}` — surfaced in the tile chrome (message length-capped, no
    HTML).
  - Any unknown/oversize message → dropped.

### Threat model + guarantees
Author with JCR write access is the adversary (same as TextTile's model). The
plugin: cannot reach the host DOM/cookies/token (opaque origin), cannot make
network requests (CSP `connect-src 'none'`), cannot navigate the top frame,
cannot inflate the page (height clamped), cannot inject MDX or widen RLS
(filter events validated), cannot XSS the host (postMessage payloads are data,
rendered as text). Data reaches the plugin only *after* RLS/PII filtering. This
is the one place untrusted code runs, so it gets a full **adversarial security
review** (as the CSS sanitiser and embed RLS did) before it's "done".

## 6. Plugin distribution + authoring

- **Distribution: an admin-installed plugin registry**, mirroring Saiku's
  existing governed-content pattern (skills / agent-spaces under `saiku-home`).
  A plugin is a **self-contained bundle** (`plugin.json` manifest + a single
  self-contained `plugin.html` used as the iframe `srcdoc`) under
  `${saiku.home}/tile-plugins/<id>/`. Admin-authored/vetted; served to the
  designer as an installed-renderer catalogue. This keeps arbitrary JS behind an
  admin gate (not any analyst) while still letting a customer add Cytoscape/deck.gl.
- **Authoring UX:** *Add tile → Custom →* pick a registered renderer (config
  renderers *and* installed plugins) → bind a cube/query → configure options
  (config tier: a form; plugin tier: the plugin's declared option schema).
- Config-tier renderers (`graph`, `echarts-option`) are built-in registry
  entries, available to any author (no install, no arbitrary code).

## 7. Integration tests — "empty → built-out → embed" (built FIRST)

Two layers, matching the existing harnesses; these land **before** the Phase-2
feature code so the feature is built on a proven end-to-end, and they cover the
**Phase-1** flow too (the "test all of this" ask):

1. **`saiku-ui/e2e/app-builder.live.spec.ts`** (Playwright *live* tier,
   `RUN_LIVE_E2E=1`, against a running launcher + seeded FoodMart): log in →
   create app from empty → add a page → **add a tile → bind to the FoodMart cube
   → Save → reload `/apps/<path>` → assert real FoodMart numbers render** → then
   the **embed leg** (mint/grant a credential, load `<saiku-embed kind="app">`,
   assert the same tile renders). Extended in the feature work to add a custom
   (config) tile and a sandboxed plugin tile.
2. **`saiku-launcher/.../it/AppBuilderIT.java`** (in-process `SaikuItHarness`,
   `mvn verify -P integration`): POST a built-out `.saikuapp` (nav + page + tiles
   binding FoodMart MDX) → GET verbatim round-trip → mint an **app** embed token
   (this is where the `ALLOWED_KINDS` fix is proven) → GET `/embed/app/{path}` +
   POST the tile-query endpoint → assert real FoodMart cells + RLS/PII behaviour.

Plus: the mocked `app-builder.spec.ts` stays as the narrow effect-regression
guard.

## 8. Phasing within Phase 2 (dependency order)

1. **Embed `app` mint fix** + `AppBuilderIT` + `app-builder.live.spec.ts` (the
   integration harness, covering Phase 1 end-to-end).
2. **Renderer registry** (refactor `Tile.svelte` + `EmbedGrid.svelte` dispatch
   to a lookup; no behaviour change — all existing types via the registry).
3. **Tier 1** — `graph` + `echarts-option` config renderers (+ Zod validation,
   + editor, + embed component). Extend the integration tests to a custom tile.
4. **Tier 2** — the iframe-sandbox `plugin` renderer + the postMessage bridge +
   the admin plugin registry (`tile-plugins/`). **Adversarial security review**
   before done. Extend the integration tests to a sandboxed plugin tile.

Each step is a spec-compliant, review-gated task; §5 (the sandbox) gets the full
adversarial treatment.

## 9. Out of scope (later)
Plugin `connect-src` network allowlist; a public plugin marketplace; plugin
versioning/signing; inputs/forms/write-back (Phase 3/4); the AI-assistant panel
(Phase 5).

## 10. Open questions for review
1. **Config `graph` vs plugin `graph`:** ship both an ECharts-`graph` config
   renderer (safe, no install) AND allow a Cytoscape *plugin* (arbitrary JS,
   admin-installed)? Leaning: yes — config for the common case, plugin for the
   bespoke.
2. **Plugin author gate:** admin-only install (recommended, matches
   skills/agent-spaces) vs. any-author inline `srcdoc` paste? Leaning:
   admin-installed registry only in Phase 2 (inline paste is a bigger blast
   radius; defer).
3. **Embed of a plugin tile:** render the sandboxed iframe inside the embed too
   (data via the token-scoped path), or degrade plugin tiles to a placeholder in
   embeds for Phase 2? Leaning: render it (the opaque-origin iframe can't reach
   the embed token, and data is already RLS/PII-filtered) — but confirm in the
   security review.

## 11. Definition of done (Phase 2)
An author can add a **custom-viz tile** to an app page — a built-in config
renderer (graph / custom ECharts) with no code, or an **admin-installed
sandboxed plugin** running arbitrary JS (Cytoscape/deck.gl) that renders real
cube data, cross-filters, and embeds — with the sandbox proven (adversarial
review) to leak nothing. And the whole flow (empty → built-out → embed) is
covered by real-backend integration tests.
