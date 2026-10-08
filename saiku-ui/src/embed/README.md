# `<saiku-embed>` — Saiku Web Component

Drop a saved Saiku query or dashboard into any page on the open web. Same
tag works in React, Vue, Svelte, and vanilla HTML — it's a real
[Custom Element](https://developer.mozilla.org/docs/Web/API/Web_components),
so the host page doesn't have to know anything about Saiku internals.

Three tags ship from this package:

| Tag                 | Use it for                                                                                                                                                     | Bundle               |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------- |
| `<saiku-chart>`     | A single chart (issue #1103)                                                                                                                                   | `saiku-chart.js`     |
| `<saiku-dashboard>` | A whole saved dashboard (issue #1103)                                                                                                                          | `saiku-dashboard.js` |
| `<saiku-embed>`     | Everything above, plus table/matrix/kpi rendering, App Builder apps (`kind="app"`), and the AI ask widget (`kind="ai"`) via one `kind`/`render` attribute pair | `saiku-embed.js`     |

Each is a separate, self-contained bundle — load only the tag(s) you use so a
page that only embeds a chart doesn't ship the dashboard-grid / AI-ask code
too. All three speak the same wire protocol, share the same
`--saiku-embed-*` CSS variable vocabulary (see "Styling" below), and can be
mixed freely on one page. `<saiku-chart>` and `<saiku-dashboard>` are
narrower views over exactly the same `kind=query`/`kind=dashboard` paths
`<saiku-embed>` has always supported — nothing here is a breaking change to
existing `<saiku-embed>` usage.

## Install

```html
<!-- pick the tag(s) you need -->
<script src="https://YOUR-SAIKU.example.com/ui/saiku-chart.js"></script>
<script src="https://YOUR-SAIKU.example.com/ui/saiku-dashboard.js"></script>
<script src="https://YOUR-SAIKU.example.com/ui/saiku-embed.js"></script>
```

Or via npm (for React / Vue / SPA projects that bundle their own JS):

```bash
npm install @concepttocloud/saiku-embed
```

```ts
import '@concepttocloud/saiku-embed'; // registers <saiku-embed>
import '@concepttocloud/saiku-embed/chart'; // registers <saiku-chart>
import '@concepttocloud/saiku-embed/dashboard'; // registers <saiku-dashboard>
```

Each import has the side effect of registering its tag globally — no
further setup. Import only the subpath(s) you use.

## Use

### A saved query, rendered as a table

```html
<saiku-embed
	server="https://YOUR-SAIKU.example.com"
	token="..."
	path="homes/admin/Examples/Trend.saiku"
	height="400px"
></saiku-embed>
```

### A saved query, rendered as a bar / line / pie chart

```html
<saiku-embed
	server="..."
	token="..."
	path="homes/admin/Examples/Sales.saiku"
	render="chart"
	mode="bar"
	height="500px"
></saiku-embed>
```

### A saved query, rendered as a hierarchical matrix (v3.19)

Matrix mode preserves the row / column axis structure — measures on
columns, dimension members on rows — instead of flattening to a single
row-key map like `render="table"` does. Useful for pivot-style reports.

```html
<saiku-embed
	server="..."
	token="..."
	path="homes/admin/Examples/Sales.saiku"
	render="matrix"
	height="500px"
></saiku-embed>
```

### An AI ask widget over a cube (v3.19, `kind="ai"`)

Point the token at a cube (rather than a saved query) and drop in a
plain-English ask box. Behind the scenes it POSTs to
`/rest/saiku/api/embed/ai/{cubeId}/ask`, which runs the question through
the server's configured LLM provider under the pinned owner's data
scope. Requires an AI-kind token (see "Minting an AI token" below).

```html
<saiku-embed
	server="..."
	token="..."
	kind="ai"
	path="foodmart/FoodMart/FoodMart/Sales"
	height="200px"
></saiku-embed>
```

### A single KPI tile (v3.20, `render="kpi"`)

The most common embed shape — one governed figure rendered large. Derives its
value from the same records response as `render="table"`, so it needs no server
change. Point it at a saved query whose last measure is the headline number;
when the query carries a prior measure column, a delta chip is shown.

```html
<saiku-embed
	server="..."
	token="..."
	path="homes/admin/Examples/NetRevenue.saiku"
	render="kpi"
	height="160px"
></saiku-embed>
```

### A saved query, sliced at embed time (v3.20, `filter`)

Pass slicer overrides as a JSON array. They ride the same validated slicer path
the dashboard filter tiles use — the saved query's cube binding and axes are
untouched, so a host can parameterise an embed without re-authoring the query.

Overrides are **narrow-only, and scoped to the saved query's own slicer
(saiku#1946)**: an override is honoured only when the saved query already
carries that hierarchy on its FILTER axis, and its members are intersected with
the members the author published on that level. An override on any other axis —
or on a different level of an authored hierarchy — is dropped and the query
renders as authored. So a `filter` can re-slice what the author already
published, never re-point the query onto a dimension the author didn't expose,
nor drill past an authored roll-up. To publish a slice, author it on the saved
query's slicer.

```html
<saiku-embed
	server="..."
	token="..."
	path="homes/admin/Examples/Sales.saiku"
	filter='[{"dimension":"Time","level":"Year","members":["[Time].[2024]"]}]'
></saiku-embed>
```

### A persona-scoped AI ask (v3.20, `space`)

Add a `space` to a `kind="ai"` embed to scope the assistant to an admin-authored
[Agent Space](../../../docs/AGENT-SPACES-SPEC.md) persona. The persona's system
prompt, skill filter, and cube allowlist apply server-side. The cube stays
pinned by the token, so a space can only **narrow** what the guest reaches — if
the space's allowlist excludes the pinned cube, the ask fails closed.

```html
<saiku-embed
	server="..."
	token="..."
	kind="ai"
	path="foodmart/FoodMart/FoodMart/Sales"
	space="foodmart-sales-analyst"
	height="240px"
></saiku-embed>
```

### A saved dashboard

```html
<saiku-embed
	server="..."
	token="..."
	kind="dashboard"
	path="homes/admin/exec.saikudash"
	height="700px"
></saiku-embed>
```

### A saved App Builder app (`kind="app"`)

Embed a whole App Builder document (`.saikuapp`) as **one token-scoped unit**.
The token grants exactly this one app; its navigation and **all** of its pages
ride that single grant — the guest sees the app read-only, switching between
pages in place.

```html
<saiku-embed
	server="..."
	token="..."
	kind="app"
	path="homes/admin/sales-portal.saikuapp"
	height="800px"
></saiku-embed>
```

Behind the scenes the bundle fetches the app document from
`GET /rest/saiku/api/embed/app/{path}` (token-scoped exactly like the dashboard
embed), then renders each page's grid through the same tile renderer the
dashboard embed uses. Every tile issues the **same** per-tile embed query a
dashboard tile does — the query body is pulled server-side from the pinned app
document (never the client), so per-query row-level security and PII redaction
are enforced identically (see "Security model" below). There is no new query
path: an app embed is purely presentational over the existing embed query path.

Mint an app token exactly like a dashboard token, with `resourceKind: "app"`
and a `.saikuapp` path (see "Server-side: minting a token"). The minter must
have GRANT on the app, and the mint-time PII gate elevates the token to
`FORCE_ON` redaction when any referenced column is PII-annotated — same posture
as a dashboard.

**Author custom CSS is designer-only in Phase 1.** The per-app custom CSS an
author sets in the App Builder is **not** applied to embeds and is **not**
embed-configurable. Embedders theme the surface through the existing
`--saiku-embed-*` CSS variables (and the `theme` attribute) just like every
other embed kind — see "Styling" below.

### Creator Mode — the visitor builds their own (`kind="creator"`, saiku#1435)

The one embed kind that **writes**. For OEM/ISV: your customer builds their own
dashboard against a cube you pinned, inside your product, under your auth.

```html
<saiku-embed
	server="..."
	token="..."
	kind="creator"
	cube="foodmart/FoodMart/FoodMart/Sales"
	height="720px"
></saiku-embed>
```

The visitor gets the pinned cube's catalogue, a rows picker, a measures picker, a
table / bar / line / pie chart, **Save query** / **Save dashboard**, and a list of
their own saved items. There is no cube switcher, no repository tree, and no MDX
editor — and the server would refuse all three anyway: an authoring token pins
one cube _and_ one `tenantId`, the folder is derived from that tenant server-side,
and every query is checked against a frozen catalogue of the pinned cube before the
MDX is generated.

Mint one token per tenant, with a short TTL:

```bash
curl -X POST 'https://YOUR-SAIKU/rest/saiku/api/embed/tokens' \
  -u admin:admin -H 'Content-Type: application/json' \
  -d '{ "resourceKind": "authoring",
        "resourcePath": "foodmart/FoodMart/FoodMart/Sales",
        "tenantId": "acme", "ttlHours": 24 }'
```

Full guide, threat model and endpoint reference:
[`docs/embed/creator-mode.md`](../../docs/embed/creator-mode.md).

### Anonymous public embed

If the resource is marked publicly embeddable on the server
(see "Public grants" below), omit the token entirely:

```html
<saiku-embed server="..." path="shared/public-chart.saiku" render="chart"></saiku-embed>
```

## `<saiku-chart>` and `<saiku-dashboard>` (issue #1103)

Purpose-built alternatives to `<saiku-embed render="chart">` and
`<saiku-embed kind="dashboard">` — same fetch, same rendering, same
security model, smaller bundle, no `kind`/`render` attribute pair to get
right.

### A single chart

```html
<saiku-chart
	server="https://YOUR-SAIKU.example.com"
	token="..."
	path="homes/admin/Examples/Sales.saiku"
	mode="bar"
	height="500px"
></saiku-chart>
```

| Attribute | Default      | Notes                                                                   |
| --------- | ------------ | ----------------------------------------------------------------------- |
| `server`  | _(optional)_ | Origin of the Saiku launcher. Leave empty for same-origin.              |
| `path`    | _(required)_ | Saved query path (`.saiku`).                                            |
| `token`   | _(none)_     | Embed token from `POST /saiku/api/embed/tokens`. Omit for public reads. |
| `mode`    | `bar`        | `bar`, `line`, or `pie`.                                                |
| `height`  | `400px`      | CSS height of the rendered surface.                                     |
| `filter`  | _(none)_     | JSON array of slicer overrides applied at embed time.                   |
| `theme`   | _(light)_    | `light`, `dark`, or `auto` (follow `prefers-color-scheme`).             |

Events: `saiku:load` (`{ kind: "chart", rows }`), `saiku:error`
(`{ message }`) — same shape as `<saiku-embed>`'s chart path.

### A whole dashboard

```html
<saiku-dashboard
	server="..."
	token="..."
	path="homes/admin/exec.saikudash"
	height="700px"
></saiku-dashboard>
```

| Attribute | Default      | Notes                                                                   |
| --------- | ------------ | ----------------------------------------------------------------------- |
| `server`  | _(optional)_ | Origin of the Saiku launcher. Leave empty for same-origin.              |
| `path`    | _(required)_ | Saved dashboard path (`.saikudash`).                                    |
| `token`   | _(none)_     | Embed token from `POST /saiku/api/embed/tokens`. Omit for public reads. |
| `height`  | `400px`      | CSS height of the rendered surface.                                     |
| `theme`   | _(light)_    | `light`, `dark`, or `auto` (follow `prefers-color-scheme`).             |

No outbound `CustomEvent`s yet — `<saiku-embed kind="dashboard">` doesn't
emit any either; per-tile interaction (filter tiles) is internal to the
dashboard grid today.

Anonymous public dashboards work the same way as query embeds — mark the
resource publicly embeddable server-side (see "Public grants" below) and
omit `token`.

## `<saiku-embed>` attributes

(`<saiku-chart>` and `<saiku-dashboard>` have their own, narrower attribute
tables above.)

| Attribute | Default                           | Notes                                                                                                                                                                                                        |
| --------- | --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `server`  | _(optional)_                      | Origin of the Saiku launcher, e.g. `https://demo.saiku.bi`. Leave empty for same-origin (v3.19+)                                                                                                             |
| `cube`    | _(none)_                          | For `kind=creator` (saiku#1435): cube ref `connection/catalog/schema/cubeName`; the token pins it, so there is no switcher. Falls back to `path` when unset                                                  |
| `path`    | _(required unless `cube` is set)_ | `kind=query`: saved query path (`.saiku`) — `kind=dashboard`: dashboard path (`.saikudash`) — `kind=ai`: cube ref `connection/catalog/schema/cubeName` — `kind=app`: App Builder document path (`.saikuapp`) |
| `kind`    | `query`                           | `query`, `dashboard`, `ai`, `app`, or `creator` (saiku#1435)                                                                                                                                                 |
| `token`   | _(none)_                          | Embed token from `POST /saiku/api/embed/tokens`. Omit for public reads                                                                                                                                       |
| `render`  | `table`                           | For `kind=query`: `table`, `matrix`, `chart`, or `kpi` (v3.20)                                                                                                                                               |
| `mode`    | `bar`                             | For `render=chart`: `bar`, `line`, or `pie`                                                                                                                                                                  |
| `height`  | `400px`                           | CSS height of the rendered surface                                                                                                                                                                           |
| `space`   | _(none)_                          | For `kind=ai`: Agent Space persona id — scopes the ask server-side (v3.20)                                                                                                                                   |
| `filter`  | _(none)_                          | For `kind=query`: JSON array of slicer overrides applied at embed time (v3.20)                                                                                                                               |
| `theme`   | _(light)_                         | `light`, `dark`, or `auto` (follow `prefers-color-scheme`) (v3.20)                                                                                                                                           |

The component re-renders whenever an attribute changes, so frameworks
binding state to attrs (React's JSX, Vue's `:server="..."`, etc.) just
work.

## Events (v3.20)

The element emits namespaced `CustomEvent`s so the host page can react to what
happens inside the embed. All bubble and are `composed`, so a listener on the
`<saiku-embed>` element receives them:

| Event            | `detail`                 | Fires when                              |
| ---------------- | ------------------------ | --------------------------------------- |
| `saiku:load`     | `{ kind, rows }`         | a query / matrix / kpi surface loads    |
| `saiku:error`    | `{ message }`            | a query load fails (friendly message)   |
| `saiku:select`   | `{ row }`                | a table row is clicked (`render=table`) |
| `saiku:ai-query` | `{ question, degraded }` | an AI ask resolves (`kind=ai`)          |

```js
const el = document.querySelector('saiku-embed');
el.addEventListener('saiku:load', (e) => console.log('loaded', e.detail.rows, 'rows'));
el.addEventListener('saiku:select', (e) => showDetail(e.detail.row));
```

In React (via `@concepttocloud/saiku-embed-react`) the same events are exposed
as `onLoad` / `onError` / `onSelect` / `onAiQuery` callback props.

## Server-side: minting a token

Authenticated as a user who has GRANT on the saved query / dashboard:

```bash
curl -X POST 'https://YOUR-SAIKU/rest/saiku/api/embed/tokens' \
  -u admin:admin \
  -H 'Content-Type: application/json' \
  -d '{
    "resourceKind": "query",
    "resourcePath": "homes/admin/Examples/Trend.saiku",
    "ttlHours": 72,
    "label": "Marketing landing page"
  }'
# → { "status": "OK", "token": "tx-...", "expiresAt": ... }
```

### Minting an AI token (v3.19)

For `kind="ai"` embeds. `resourcePath` is a cube ref rather than a
file path. Mint is admin-only for v1 (cube-level ACLs are a follow-up).

```bash
curl -X POST 'https://YOUR-SAIKU/rest/saiku/api/embed/tokens' \
  -u admin:admin \
  -H 'Content-Type: application/json' \
  -d '{
    "resourceKind": "ai",
    "resourcePath": "foodmart/FoodMart/FoodMart/Sales",
    "ttlHours": 72,
    "label": "DimSum widget on marketing site"
  }'
```

Server-side, an AI ask requires the launcher to have an LLM provider
configured (`saiku.ai.ask.provider = anthropic | openai` plus the
matching API key). Without it, the widget renders a degraded message
rather than an error.

Paste the `token` into the host page's `<saiku-embed token="...">`.
Tokens are server-authoritative — revoke any time via:

```bash
curl -X DELETE 'https://YOUR-SAIKU/rest/saiku/api/embed/tokens/<token>' \
  -u admin:admin
```

## Server-side: public grants

To make a resource readable WITHOUT a token (e.g. for a public blog post):

```bash
curl -X POST 'https://YOUR-SAIKU/rest/saiku/api/embed/public' \
  -u admin:admin \
  -H 'Content-Type: application/json' \
  -d '{
    "resourceKind": "query",
    "resourcePath": "shared/public-chart.saiku",
    "label": "Homepage chart"
  }'
```

Public reads still run under the grantor's data scope, so any
session-injected filters render from the grantor's perspective.
Revoke:

```bash
curl -X DELETE 'https://YOUR-SAIKU/rest/saiku/api/embed/public?kind=query&path=shared/public-chart.saiku' \
  -u admin:admin
```

## Styling

The embed lives inside an
[open shadow root](https://developer.mozilla.org/docs/Web/API/ShadowRoot),
so host page CSS can't leak in and vice versa.

For a quick dark surface, set `theme="dark"` (or `theme="auto"` to follow the
viewer's `prefers-color-scheme`) — it swaps the whole palette without the host
having to set each variable (v3.20). Leaving `theme` unset keeps the original
light palette, so existing embeds are unchanged.

To fine-tune individual colours, set CSS variables on the host page:

```css
saiku-embed {
	--saiku-embed-fg: #0f172a;
	--saiku-embed-bg: transparent;
	--saiku-embed-border: #cbd5e1;
	--saiku-embed-header-bg: #f1f5f9;
	--saiku-embed-tile-bg: #ffffff;
	--saiku-embed-row-hover: #e2e8f0;
	--saiku-embed-negative: #b91c1c;
	--saiku-embed-error: #b91c1c;
	--saiku-embed-muted: #64748b;
}
```

Creator Mode (`kind="creator"`, saiku#1435) adds three variables for the builder
chrome, each defaulting to the existing tokens so a host that sets nothing new
still looks right: `--saiku-embed-surface` (panel background),
`--saiku-embed-control` (select / input background) and
`--saiku-embed-accent-soft` (control hover / selected background).

### Theming the chart itself

The variables above style the embed **chrome** (frame, header, table). To brand
the **chart series + axes**, set these (custom properties inherit through the
shadow boundary, so the canvas chart picks them up):

```css
saiku-embed {
	/* Series colour cycle — set as many as you need, 1..8, contiguously.
     Any unset → the chart falls back to the built-in palette. */
	--saiku-embed-chart-1: #2563eb;
	--saiku-embed-chart-2: #16a34a;
	--saiku-embed-chart-3: #dc2626;
	/* …up to --saiku-embed-chart-8 */

	/* Axis labels / legend / titles use --saiku-embed-fg;
     axis + split lines use --saiku-embed-muted (both shared with the chrome). */
}
```

An embed with none of these set renders exactly as before (ECharts defaults).

## Security model

- **Header-only token transport.** The token travels as
  `X-Saiku-Embed-Token`. Never a `?token=` query parameter — those leak
  into access logs, proxy logs, browser history, and outbound
  `Referer`.
- **Creator Mode writes are a URL prefix, not a verb.** The embed identity is
  admitted only under `/rest/saiku/api/embed/authoring/**` and only with a
  second role (`ROLE_EMBED_AUTHOR`) that a read token never carries. The target
  path is always re-derived from the token's `tenantId`; no endpoint there accepts
  a caller-supplied path, and object names are reduced to a single safe path
  segment. Creator queries are `QUERYMODEL`-only — raw MDX, filters, sort
  expressions, calculated members, named sets and query parameters are refused —
  and every hierarchy / level / member / measure is checked against a frozen
  catalogue of the pinned cube.
- **Server-side authoritative.** Tokens are opaque random 256-bit ids
  with no embedded claims; the server looks them up on every request.
  Revocation takes effect on the very next request.
- **Per-resource scope.** A token pins exactly one query, dashboard, cube,
  or app — or, for Creator Mode, exactly one cube _and_ one tenant. Replaying it against any other resource (or any other endpoint)
  returns the same opaque `EMBED_INVALID` 401, regardless of whether
  the request used the wrong kind, the wrong path, an expired token,
  or a revoked one. Probes can't enumerate. An `app` token grants the one
  `.saikuapp` document as a unit — the doc + every page's tile query is
  served under that single pin, so a token for app A can't read app B or
  any arbitrary repository path.
- **RLS / PII stay fail-closed on every kind.** App-page tile queries run
  through the exact same guarded execution as dashboard tiles: forced
  row-level-security filters from the token are applied **last** and fail
  closed (`RLS_UNAPPLIED` / `EMBED_RLS_UNSUPPORTED`) if they can't be
  spliced, and a `FORCE_ON` redaction policy still emits its gateway header.
  A client `filter=`/filter-tile override can only **narrow** a query, never
  widen it. Embedding an app adds no new query path.
- **Bare saved-query embeds are scoped too.** A `kind=query` embed has no
  author-declared filter panel or filter tiles, so the saved query's own FILTER
  axis is the scope (saiku#1946): overrides outside it are dropped, and the
  members of an honoured override are intersected with the authored ones. An
  override that can't be proven to narrow (unknown axis, different level, a
  non-`in` operator, an empty intersection) is dropped — the query runs as
  authored rather than failing open. This matters most for public grants and
  pre-`saiku#1104` opaque tokens, which carry no forced RLS filters at all.
- **Cross-origin cookie isolation.** The embed sends
  `credentials: "omit"`, so the host page's Saiku session cookie (if
  the user happens to be logged in) doesn't flow with embed reads.
  The token IS the only auth carrier on this surface.

## Bundle size

Gzipped, at the time of writing:

| Bundle               | Size    |
| -------------------- | ------- |
| `saiku-chart.js`     | ~207 KB |
| `saiku-dashboard.js` | ~272 KB |
| `saiku-embed.js`     | ~279 KB |

All three share the same Svelte 5 custom element runtime + ECharts (core +
bar / line / pie + four common components, modular tree-shaken) base.
`saiku-chart.js` drops the dashboard grid, App Builder, table, matrix, kpi,
and AI-ask code `saiku-embed.js` also carries, so it's meaningfully
smaller. `saiku-dashboard.js` still pulls in most of that renderer set
(the dashboard grid dispatches to chart / kpi / filter / text / custom-tile
renderers — see EmbedGrid.svelte), so the saving there is modest; its value
is a narrower, purpose-named tag rather than a smaller download.

Pick whichever tag(s) a given page actually uses — they load independently
and don't share a runtime at the script-tag level (each `<script src>` is a
fully self-contained IIFE, per the "single self-contained file" design in
`vite.config.embed.ts`).

## Limitations

- Records-format only. The matrix format isn't rendered in v1.
- Dashboard `filter` tiles are skipped — the embed renders the
  authored data as-is without an interactive filter bar.
- Markdown in `text` tiles renders as plain text (no `marked`
  dependency to keep the bundle tight).
- AI Query results (`/ai/query`) aren't wired as an `<saiku-embed>`
  source yet — coming in a follow-up.
- Creator Mode has no drag-and-drop grid yet: a saved dashboard is a single
  saved query plus a chart type. Tiles, cross-filtering and a full layout
  builder are the obvious follow-up; the save path already stores whatever
  `layout` you send, so the document shape won't need to change.
