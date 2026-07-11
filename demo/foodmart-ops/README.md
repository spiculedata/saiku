# FoodMart Ops — embeddability demo

A single-page, **FoodMart-branded** store-operations portal for a fictional OEM: the
company that runs FoodMart built an internal app for its store/regional managers, and
**Saiku is embedded white-label** as the "Ask FoodMart" assistant. End users never see
the word *Saiku* — they just ask questions in plain English and the dashboard updates.

It's one self-contained `index.html` (no build step, no CDN, hand-rolled SVG charts).

## Where it lives / how it deploys

The served copy is bundled into the webapp at:

```
saiku-webapp/src/main/webapp/embed-demo/index.html   →  /embed-demo/
```

`/embed-demo/**` is a public (`security="none"`) static path in
`applicationContext-saiku.xml`, next to `/showcase/**`. Every push to `development`
rebuilds the rolling demo image (`.github/workflows/docker.yml`), so once this merges it
ships to **<https://demo.saiku.bi/embed-demo/>** on the next demo reset.

## How it talks to Saiku (live)

The assistant calls the persona-scoped SSE endpoint we ship:

```
POST /rest/saiku/api/ai/spaces/foodmart-sales-analyst/ask/stream
```

Served **same-origin**, so it warms an `admin/admin` session via `/rest/saiku/session/`
on load (same bootstrap the `/showcase` demo uses) and echoes the `X-XSRF-TOKEN` on the
POST — anonymous demo visitors get live streaming with no login step. The AI endpoints
stay behind `/rest/**` auth **and** the ask rate-limiter + policy guard, so the page being
public does not open the LLM to unbounded anonymous use.

Live requires an LLM provider configured on the host (`saiku.ai.ask.provider` + key) and
the `foodmart-sales-analyst` space seeded (it is, on fresh installs). If either is
missing the assistant shows the degraded reason and falls back to demo answers.

## Local preview (demo mode)

Open the file directly — no backend needed:

```bash
open saiku-webapp/src/main/webapp/embed-demo/index.html
```

`file://` can't reach a same-origin API, so it runs in **demo mode**: the assistant
streams scripted, FoodMart-shaped answers (word-by-word, mimicking the real SSE) and
drives the dashboard. The source badge reads **"Demo data."** The ⚙ settings dialog can
point at a base URL for cross-origin testing, but that path needs CORS on Saiku —
same-origin is the intended route.

## What it showcases

- **White-label embed** — host brand on top, Saiku underneath, "powered by Saiku" the only tell.
- **Agent Spaces** — scoped to the `foodmart-sales-analyst` persona (Sales cube only, analyst voice).
- **SSE streaming** — answers arrive progressively, `model → intent → chunk → final`.
- **AI drives the host UI** — a `QUERY` result re-renders the main chart; a `VIEW_CHANGE` swaps it.
