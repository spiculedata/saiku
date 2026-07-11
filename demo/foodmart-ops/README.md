# FoodMart Ops — embeddability demo

A single-page, **FoodMart-branded** store-operations portal for a fictional OEM: the
company that runs FoodMart built an internal app for its store/regional managers, and
**Saiku is embedded white-label** as the "Ask FoodMart" assistant. End users never see
the word *Saiku* — they just ask questions in plain English and the dashboard updates.

It's one self-contained `index.html` (no build step, no CDN, hand-rolled SVG charts).

## Two ways to run it

### 1. Demo mode (instant, no backend)
Just open the file:

```bash
open demo/foodmart-ops/index.html
```

The assistant streams scripted, FoodMart-shaped answers (word-by-word, mimicking the real
SSE wire format) and drives the dashboard — the breakdown chart rebuilds, the trend
switches, movers re-rank. Good enough to show the whole story to anyone, anywhere. The
source badge reads **"Demo data."**

### 2. Live mode (real Saiku streaming)
The assistant calls the real endpoint we ship:

```
POST /rest/saiku/api/ai/spaces/foodmart-sales-analyst/ask/stream
```

— the persona-scoped SSE streaming surface. To make the live calls work you need
**same-origin serving** (how a real OEM embeds: same host, or a reverse proxy) so the
browser session cookie is sent and there's no CORS. Two options:

- Copy `index.html` into the running Saiku webapp (e.g. under a static path the launcher
  serves) and browse to it on `http://localhost:8080/...`, **or**
- Put a reverse proxy in front of both the portal and Saiku on one origin.

Then log into Saiku (admin/admin) in the same browser and open the portal. The badge
flips to **"Live · Saiku."** Requires an LLM provider configured
(`saiku.ai.ask.provider` + API key) or the assistant shows the degraded reason and falls
back to demo answers.

> The ⚙ settings dialog lets you point at a base URL (e.g. `http://localhost:8080`) for
> cross-origin testing, but that path needs CORS enabled on Saiku — same-origin is the
> intended, friction-free route.

## What it showcases

- **White-label embed** — host brand on top, Saiku underneath, "powered by Saiku" the only tell.
- **Agent Spaces** — the assistant is scoped to the `foodmart-sales-analyst` persona (Sales
  cube only, analyst voice, its suggested prompts as quick chips).
- **SSE streaming** — answers arrive progressively, `model → intent → chunk → final`.
- **AI drives the host UI** — a `QUERY` result re-renders the main chart; a `VIEW_CHANGE`
  swaps the chart type. The assistant reaches into the dashboard.
