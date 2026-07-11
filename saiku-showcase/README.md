# saiku-showcase

Single-page playground that proves Saiku's three developer surfaces work end-to-end:

1. `<saiku-embed>` web component — table / matrix / chart renders against a live cube.
2. AI Query API — interactive form → `POST /rest/saiku/api/ai/query` → typed response.
3. GraphQL API — mini editor + result panel → `POST /rest/saiku/api/graphql` + SDL fetch.

All widgets hit [`demo.saiku.bi`](https://demo.saiku.bi) — the public FoodMart demo. Nothing is stubbed.

## Deploy

Pure static HTML + one inline script. No build step. Drop `index.html` anywhere that can serve static files:

- Behind Cloudflare Pages / Netlify / any static host.
- Alongside the Saiku launcher — the launcher's Jetty EE10 serves anything under `/saiku-home/plugins/showcase/` at `/plugins/showcase/`.
- In its own container: `docker run -v $(pwd):/usr/share/nginx/html:ro -p 8000:80 nginx`.

## CORS

The AI Query and GraphQL sections make cross-origin requests from wherever this page is hosted to `demo.saiku.bi`. `demo.saiku.bi` needs to allow the deployed origin in its CORS config. The bundled `<saiku-embed>` component is the same script `demo.saiku.bi` ships, so the embed section works from any origin.

## Session

The AI Query and GraphQL sections warm a session via `POST /rest/saiku/session/` with `admin/admin` (the demo credentials) on load. Every visitor shares the same anonymous session — fine for a public showcase, not for production. Cookies + CSRF tokens are forwarded on each subsequent request.

## Files

- `index.html` — the whole thing.
- `README.md` — this file.
