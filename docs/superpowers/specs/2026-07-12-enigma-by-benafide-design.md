# Enigma by Benafide — Design

**Status:** Draft for review · **Date:** 2026-07-12 · **Owner:** Tom Barber

## 1. What this is

**Enigma by Benafide** is a public showcase application for beneficial-ownership
(UBO) analysis, built over Benafide's ~100M-row corporate-registry dataset. It
is *nominally* a demo, but it must be a **genuinely functioning application**,
not a click-through.

The real purpose is to prove the **Spicule / Saiku stack** on a compelling,
real-world dataset:

- **Ossie** semantic modelling (the `benafide.ossie.yaml` model)
- **Saiku** analytics + embedding over **DuckDB** (live, via the native Quack protocol)
- **AI** that reasons over the semantic model
- **Extra analysis** (ownership-graph traversal, UBO resolution, risk) layered on top

Benafide already runs its own internal KYB/UBO product; Enigma is a distinct,
freely-branded public surface that demonstrates what the stack can do.

## 2. Data foundation (already in place)

The dataset is a beneficial-ownership graph sourced from Nordic + EU registries
(CVR 🇩🇰, BRREG 🇳🇴, INPI 🇫🇷, Companies House 🇬🇧, and more), stored in a ~28 GB
DuckDB file `lineage.db` on `lineage-prod` (Hetzner):

| Table | Rows | Role |
|---|---|---|
| `entities` | 45.7M | Companies / legal entities |
| `ownership_statements` | 24.4M | Ownership/control edges (the graph) |
| `persons` | 11.2M | Natural-person beneficial owners (PII) |
| `entity_risk` | 46.6M | Computed risk score + factors |
| `entity_features` | — | Opacity score, structural features |
| `risk_flags` | 457 | Sanctions / PEP / adverse-media matches |
| `watchlists`, `watchlist_events` | — | Saved monitoring |

Two backends already sit on this DuckDB, both reachable over the Tailscale tailnet:

1. **Saiku** — queries the DuckDB *live* over the native **Quack** protocol
   (`quack-jdbc`, `benafide-quack.service` on `lineage-prod:9494`, read-only,
   token-authed). The **`benafide.ossie.yaml`** Ossie model exposes datasets
   (`entity`, `person`, `ownership_statement`, `entity_risk`) + metrics
   (ownership_count, company_count, owner_count, avg_ownership_pct,
   avg_risk_score). Reachable via `/rest/saiku/api/ai/ossie/*` and `<saiku-embed>`.
2. **Benafide KYB/UBO API** (FastAPI, `/srv/lineage/app`) — already implements the
   graph and entity layer:
   - `GET /graph` → `{entity, ubos[], edges[], circular_flags[], total_chains, max_depth}` — ownership graph **with circular-loop detection**
   - `GET /entities/{id}/ubo` — UBO resolution
   - `GET /entities`, `/entities/{id}`, `/entities/{id}/risk`
   - `GET /risk`, `/reviews`, `/metrics/*`, `/watchlists`, `/export`
   - opacity scores (`entity_features`), sanctions-family detection

Enigma builds on both — it does **not** re-implement graph traversal.

## 3. Architecture

Enigma is a **standalone SvelteKit app** that federates the two backends. Its
own SvelteKit server also acts as a **proxy** so the browser never needs direct
access to tailnet-internal services.

```
              ┌──────────────── ENIGMA (SvelteKit 2 / Svelte 5) ────────────────┐
              │   branded shell  ·  custom viz (Cytoscape.js, deck.gl, ECharts)  │
              │   SvelteKit server = SSR + reverse-proxy to tailnet backends     │
              └────────┬────────────────────────────────────────────┬───────────┘
   analytics + AI      │                                            │   graph · entities · risk
  (Ossie semantic model)▼                                            ▼   (already built)
            ┌───────────────────────┐                    ┌───────────────────────────┐
            │  SAIKU (public)        │                    │  Benafide KYB/UBO API      │
            │  /ai/ossie/query +ask  │                    │  /graph /ubo /entities     │
            │  <saiku-embed …>       │                    │  /risk /watchlists /metrics│
            └──────────┬────────────┘                    └────────────┬──────────────┘
                       │ quack-jdbc (live)                            │ duckdb read-only
                       └───────────────►  DuckDB  lineage.db  ◄───────┘  (Quack, read-only)
```

### Tech choices

- **Framework:** SvelteKit 2 + Svelte 5 (runes). Matches Saiku's stack; `<saiku-embed>`
  is a Svelte web component so it drops in natively.
- **Analytics / AI:** `<saiku-embed>` (first-class — see §6) + `/ai/ossie/query`,
  `/ai/ossie/ask`. An **"Enigma Investigator" Agent Space** scopes AI to the
  Benafide cube with an investigative persona.
- **Graph viz:** **Cytoscape.js** — hierarchical + force layouts, rich node/edge
  styling, natural for unfold-to-UBO and circular-loop highlighting.
- **Map viz:** **MapLibre GL + deck.gl `ArcLayer`** for animated cross-border flows.
- **Bespoke charts:** ECharts (same engine Saiku uses).
- **Data access:** SvelteKit `load` + a thin client per backend; server-side
  proxy routes for the tailnet-internal Benafide API.

## 4. The experience — seven pillars (v1)

1. **The Web** *(signature)* — global search → an entity's ownership graph unfolds
   outward to ultimate humans. Subject in cyan, intermediates neutral, ultimate
   owners in amber, flagged owners in red, **circular loops in red with an alert**.
   Cytoscape.js fed by `/graph`. Fuses Reveal/Signals/Cases/Ask into one investigation view.
2. **The Reveal** — full UBO chain breakdown: effective control % computed hop-by-hop,
   alternate control paths, sanctions/PEP chips. `/entities/{id}/ubo`.
3. **The Deck** — Saiku BI dashboards over Ossie: ownership by jurisdiction, status
   mix, risk distribution, ownership-change timeline, top owners. Via `<saiku-embed>`.
4. **Ask Enigma** — chat: natural language → typed Ossie query → answer + chart,
   scoped by the Enigma Investigator Agent Space. `<saiku-embed kind=ai>` / `/ai/ossie/ask`.
5. **Signals** — risk & sanctions radar: risk heatmap, flagged-entity table,
   sanctions/PEP hits, opacity leaderboard. `/risk` + Ossie aggregates.
6. **Borderlines** — deck.gl arc map of cross-border ownership flows; click a flow
   to drill. Ossie jurisdiction-pair aggregates + `/entities`.
7. **Case Files** — pin entities/owners/flags → saved case → exportable dossier.
   `/watchlists` + `/export`.

*Deferred (post-v1):* **Under the Hood** — Ossie YAML → generated SQL → DuckDB
result, live. Its stack-proof lives quietly inside the other screens for now
(status bar, provenance panel).

## 5. Branding & design system

A dedicated **Enigma design system** (its own tokens, separate from Saiku's):

- **Canvas:** near-black (`#08090c`), layered panels.
- **Signal palette:** amber = ownership/UBO, cyan = subject/data, red =
  risk/sanctions, violet = AI, green = live/status.
- **Type:** Fraunces (display) · JetBrains Mono (data/labels) · Inter Tight (body).
- **Texture:** faint blueprint grid + glow accents; "intelligence / cipher" mood.
- Co-branded **"Enigma by Benafide."**

Reference mockups: `.superpowers/brainstorm/…/enigma-concept.html`,
`enigma-shell-web.html` (approved).

## 6. Saiku-side workstream — embed hardening

Enigma uses `<saiku-embed>` for real. Where the embed component does not yet
render **Ossie** models (proven today for MDX cubes, saved queries, dashboards),
we **extend the embed code in Saiku** as part of this work — a deliberate dogfood
test of the embedding capability. Scope to confirm/build:

- `<saiku-embed kind="query">` and `kind="dashboard"` over **Ossie** saved
  queries / dashboards (embed-token resource layer + renderer).
- `<saiku-embed kind="ai">` over an **Ossie** cube + Agent Space.
- Any Ossie gaps in the embed-token minting / public-grant path.

This is tracked as a first-class deliverable, not a fallback.

## 7. Deployment topology (demo VM)

The **application tier** runs on the demo VM (`demo.saiku.bi`, Scaleway); the
**data tier** stays on `lineage-prod` (Hetzner) and is reached over the tailnet.

- **Public (demo VM):** Enigma (SvelteKit, SSR + proxy) and Saiku (analytics /
  embed / AI). Saiku is public so browser-side embeds load; it reaches DuckDB via
  Quack over the tailnet.
- **Tailnet-internal (lineage-prod):** DuckDB + `benafide-quack` (:9494) +
  Benafide KYB/UBO API. The browser never touches these directly — Enigma's server
  proxies the Benafide API; Saiku proxies analytics.
- **Phase-0 infra tasks:** join the demo VM to the tailnet; publish Enigma
  (e.g. `enigma.saiku.bi` or `demo.saiku.bi/enigma`); confirm Saiku on the demo VM
  can reach `lineage-prod:9494`; secure the Quack token + a read-only Benafide API
  scope. *(If instead the intent is to relocate the DuckDB + API onto the demo VM,
  flag on review — this spec assumes app-tier-on-demo, data-tier-on-lineage.)*

## 8. Build phasing

- **Phase 0 — Foundation:** SvelteKit scaffold + Enigma design system + app shell
  + global search / entity resolution; API clients (Saiku + Benafide) + server
  proxy; deployment topology (§7).
- **Phase 1 — The Web + The Reveal:** Cytoscape graph explorer + UBO reveal
  (`/graph`, `/ubo`). The signature. *(Start here.)*
- **Phase 2 — The Deck + Ask Enigma:** Saiku Embed dashboards + AI over Ossie +
  Agent Space; harden embed (§6) as needed.
- **Phase 3 — Signals + Borderlines:** risk radar + deck.gl flow map.
- **Phase 4 — Case Files + polish:** dossier export, motion, empty states, curated
  demo entities so it always demos well.

## 9. Risks & open items

- **Embed-over-Ossie gaps** (§6) — mitigated by owning the Saiku code.
- **Query performance** — cold aggregates over 24M×45M joins run ~60s today;
  Phase 2/3 need caching, pre-aggregation, or narrowed queries for interactive feel.
- **PII / GDPR** — persons carry name/DOB/nationality/address under a lawful-basis
  column. Enigma must respect k-anonymity + PII redaction (Saiku features) and show
  provenance; prefer aggregates; individual detail gated appropriately.
- **Alpha driver** — `quack-jdbc 0.1.0-alpha.1` (stabilises with DuckDB v2.0, Sept 2026).
- **Demo-VM ↔ lineage-prod reachability** — depends on tailnet join (Phase 0).

## 10. Success criteria

- A visitor can search a real company and watch its ownership graph resolve to
  ultimate humans, with risk/sanctions surfaced — end to end, on live data.
- The Deck and Ask Enigma run on live Saiku analytics over the Ossie model.
- It looks and feels like a premium, branded product — not a BI tool with a skin.
- The stack story (Ossie → Saiku → DuckDB/Quack → AI + analysis) is legible to a
  technical evaluator without a slide deck.
