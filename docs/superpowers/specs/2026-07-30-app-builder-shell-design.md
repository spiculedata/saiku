# Saiku App Builder — Phase 1: App Shell + Navigation — Design

**Status:** Draft for review · **Date:** 2026-07-30 · **Owner:** Tom Barber

## 1. Why

A customer, shown the three API-driven demos (`/embed-demo/`, `/aqvira-demo/`,
`/enigma/`), asked: *"can't we use the dashboard designer to build these types of
apps?"* Today, no — all three are **~100% hand-written host apps that use Saiku
as a headless backend** (REST/SSE AI ask, `/ai/query`, `/scenario/whatif`,
`/forecast`, `/anomaly`, agent spaces). None use the dashboard designer,
`<saiku-embed>`, the React SDK, or iframes. What they showcase is a *different
axis* from the tile-grid: a **branded app shell**, **multi-page navigation**, an
**AI-assistant that drives the view**, **advanced-analytics panels**, and (enigma)
**custom viz + write-back**.

The agreed direction is to evolve the dashboard designer into an **app builder**
(the full extensible platform, incl. custom tiles + write-back). That is a
*program*, not a feature; it decomposes into sub-projects each with its own
spec → plan → build cycle.

### The App Builder program (decomposition, dependency order)
1. **App shell + navigation** ← *this document (Phase 1)*
2. Custom-tile / plugin framework (+ sandbox security)
3. Inputs + action/event model
4. Write-back (endpoints, persistence, authz, audit)
5. AI-assistant panel that drives tiles
6. Advanced-analytics tiles (what-if / forecast / anomaly first-class)

**Cross-cutting pillar (all phases): security.** Today's designer is
deliberately locked down (DOMPurify text tiles, read-only queries, fail-closed
RLS/PII, opaque embed tokens). Custom tiles (#2), write-back (#4) and custom CSS
(this phase) each open holes in that model and must be designed against it, not
after it.

## 2. Scope of this phase

**In:** the container everything else nests in — a branded shell (configurable
nav + header + theme) wrapping an ordered set of **pages**, each page an inline
dashboard canvas that reuses the existing grid/tiles unchanged. A classic
single dashboard remains valid and unchanged (it is, conceptually, a one-page
app). The whole app is one atomic, portable document for share / embed / RLS.

**Out (later phases):** custom-tile/plugin framework, input/button/form widgets,
action/event model, write-back, AI-assistant wiring (this phase reserves a shell
*slot* for it but does not wire it), advanced-analytics tiles.

## 3. Key decisions (agreed in brainstorm)

| Decision | Choice | Rationale |
|---|---|---|
| Document model | **New `app` document that wraps dashboards as pages** | Reuses all existing dashboard/tile/grid machinery; dashboards untouched; app layer is a thin wrapper. |
| Page content | **Inline / self-contained** (page holds its own grid) | One atomic, portable doc → simplest share/embed/RLS/versioning; matches the self-contained demos; no dangling refs. "Start page from an existing dashboard" imports a copy. |
| Nav chrome | **Configurable: rail OR top tabs** (header always) | Authors pick app-like (rail) vs dashboard-like (top). |
| Branding | **Curated tokens + scoped custom CSS** | Tokens cover the demo look with zero risk; custom CSS gives power users control — designed against the security pillar (see §7). |

## 4. Document model — `.saikuapp`

Stored in the JCR repo as a new resource type alongside `.saikudash`. The server
treats it as **opaque JSON** (same posture as `DashboardResource` — the UI is the
schema authority). Schema (v1):

```jsonc
{
  "id": "…",
  "name": "FoodMart Ops",
  "version": 1,
  "logo": "<asset-ref>",              // uploaded via the existing hardened asset endpoint
  "theme": {
    "mode": "light" | "dark" | "auto",
    "primary": "#2f5d3a",
    "accent":  "#e2725b",
    "bg":      "#…",  "fg": "#…",
    "font":    "serif-1",             // from a curated allowlist (no arbitrary @font-face URLs)
    "customCss": "/* scoped + sanitised — see §7 */"
  },
  "nav": {
    "position": "rail" | "top",
    "items": [ { "pageId": "…", "label": "Overview", "icon": "grid" } ]
  },
  "assistantSlot": { "enabled": false }, // reserved for Phase 5; renders nothing yet
  "pages": [
    {
      "id": "…",
      "title": "Overview",
      "icon": "grid",
      "grid": { /* exactly today's DashboardLayout: cols, tiles[], filters, filterPanel */ }
    }
  ],
  "tags": []
}
```

- **`pages[].grid` is byte-for-byte the current dashboard layout object.** No fork
  of the grid/tile code — the page renderer *is* the existing dashboard renderer,
  pointed at `page.grid`. Every future tile/chart improvement benefits apps for free.
- **Backward compatibility:** existing `.saikudash` docs are untouched and keep
  opening in the dashboard editor. An app with one page whose `grid` is a copied
  dashboard is the migration path ("New app from this dashboard"). No forced
  migration; the two coexist.

## 5. Shell UI

- **Header** (always): logo, app name, global controls (page-independent filters
  later; for now: theme/mode toggle, share/embed, edit/present).
- **Navigation** (`nav.position`):
  - `rail` — left icon+label rail, collapsible; the "app" look (embed-demo/aqvira).
  - `top` — tab bar under the header; the "dashboard" look.
  - Responsive: below the existing 768px breakpoint the rail becomes a bottom tab
    bar and top-tabs wrap/scroll — reusing `responsiveLayout.ts` conventions.
- **Page region**: renders the active page's grid via the existing
  `DashboardEditor`/grid components (read + edit).
- **Reserved assistant slot**: a right-hand region the shell lays out but leaves
  empty in Phase 1 (`assistantSlot.enabled=false`). Phase 5 fills it. Reserving it
  now means the shell's grid/flex math doesn't churn later.
- **Editor vs viewer**: the app editor adds page management (add/rename/reorder/
  delete pages, import-from-dashboard, set nav/theme) around the existing tile
  editor. The viewer is the same shell in read-only mode.

## 6. Backend, embedding, security-of-transport

- **New JAX-RS resource** `AppResource` (mirrors `DashboardResource`): CRUD on the
  opaque `.saikuapp` JSON, history/versioning, comments — reuse the dashboard
  services where they're doc-agnostic.
- **Embedding**: extend `<saiku-embed>` with `kind="app"` (renders the shell +
  pages). App share links and embed tokens scope the **whole app as one unit** —
  which is exactly why inline/self-contained pages were chosen. **RLS/PII ride the
  token and apply per-tile-query as today, fail-closed** — the app wrapper adds no
  new query path, so the existing embed security model carries over unchanged.
- No new query surface in this phase: pages issue the same `/ai/query` calls the
  dashboard does.

## 7. Security — scoped custom CSS (the one new hole this phase opens)

Custom CSS is the only Phase-1 feature that expands the attack surface. Design:

1. **Scope**: the app shell renders inside a container with a unique root
   (`[data-saiku-app="<id>"]` or, on the embed surface, the existing shadow root).
   All author CSS is **prefixed/scoped to that root** at save time (parsed, every
   selector rewritten to descend from the app root) so it cannot style Saiku
   chrome outside the app or leak across embeds.
2. **Sanitise** (allowlist, reject-on-violation, at save + re-validate at render):
   - **No `@import`, no `@font-face`, no `url()` to remote origins** (only
     `data:` and same-origin asset refs permitted).
   - **No `position: fixed`** (prevents overlay/escape of the app bounds);
     `position: absolute` only within the scoped root.
   - No `behavior`, no CSS `expression()`, no `-moz-binding`.
   - Reject anything the parser can't round-trip (defence against smuggling).
3. **CSP**: the app/embed responses already send a strict CSP; custom CSS is
   inline-scoped and covered by `style-src` — no relaxation.
4. **Fail-closed**: if sanitisation can't fully parse/scope the CSS, it is
   **dropped** (app still renders on tokens), never passed through raw.

This keeps the "author can't break out of their own surface" guarantee that the
DOMPurify text tile already establishes for HTML.

## 8. Testing

- **Model/schema**: round-trip serialise/deserialise `.saikuapp`; a 1-page app
  renders identically to the equivalent standalone dashboard (parity test).
- **Nav**: rail ↔ top switch; responsive collapse; page routing keeps per-page
  filter/URL state (extend `urlFilterState.ts` with a page dimension).
- **Custom-CSS sanitiser**: property-based tests (this repo now has Hegel —
  `saiku-proptest`) asserting the scoper never emits an unscoped selector and the
  sanitiser never passes a forbidden token (`@import`, remote `url()`,
  `position:fixed`, …) for *any* input. This is a textbook injection-invariant PBT.
- **Embed**: `kind="app"` renders; RLS/PII still fail-closed per tile.
- **Back-compat**: existing dashboards open unchanged; floors/CI green.

## 9. Open questions for review

1. **Per-page vs per-app filters.** Do panel filters live per-page (current
   dashboard behaviour, repeated per page) or can an app declare *app-level*
   filters that persist across pages (aqvira's therapeutic-area picker spans
   tabs)? Leaning: support both — page filters now, an optional app-level filter
   bar as a small addition this phase or the next.
2. **Nav `items` vs `pages` ordering.** Keep nav a separate ordered list
   referencing pages (allows hidden/utility pages), or derive nav 1:1 from
   `pages[]`? Leaning: derive 1:1 for v1, add explicit nav items when hidden pages
   are needed.
3. **Where the app editor lives** — a new top-level "Apps" section in the repo
   browser alongside "Dashboards", or apps surface within the dashboard catalogue
   filtered by type? Leaning: new section for clarity.
4. **Custom-CSS availability** — designer-only, or also editable on the embed
   surface? Leaning: designer-only in Phase 1 (embedders already have the
   `--saiku-embed-*` token vars).

## 10. Definition of done (Phase 1)

An author can create a **multi-page, branded app** in Saiku — configurable
nav (rail/top), header + logo, theme tokens + (safely scoped) custom CSS, pages
that reuse the full existing tile palette — save it as one `.saikuapp` doc, and
**share / embed it as a single unit** with RLS/PII intact. It visibly resembles
the embed-demo/aqvira *shell* (minus the AI panel + advanced tiles, which are
later phases). Existing dashboards are unaffected.
