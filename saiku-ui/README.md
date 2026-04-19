# saiku-ui

SvelteKit 2 + Svelte 5 (runes) + TypeScript (strict) rewrite of the Saiku UI.
Phase 4 owns the full feature-complete port of the legacy Backbone UI; see
[`docs/plans/2026-04-19-phase-4-sveltekit-port.md`](../docs/plans/2026-04-19-phase-4-sveltekit-port.md)
for the view-by-view inventory and slice ordering.

The original Backbone tree lives under `../saiku-ui-legacy/` as a read-only
porting reference. It is deleted once the inventory hits 100 %.

## Dev flow

```sh
# Terminal 1 — run the Saiku server (embedded Jetty)
cd ..
mvn -DskipTests -Dmaven.test.skip=true -pl saiku-launcher -am package
java -jar saiku-launcher/target/saiku-3.17.jar serve --port 8080

# Terminal 2 — run the UI in SvelteKit dev mode (proxies /rest/* to :8080)
cd saiku-ui
npm install
npm run dev
# open http://localhost:5173, log in with admin / admin
```

Set `SAIKU_API=http://host:port` before `npm run dev` to point at a different
backend.

## Scripts

- `npm run dev` — Vite/SvelteKit dev server with HMR
- `npm run build` — produce static `dist/`
- `npm run preview` — serve the built bundle locally
- `npm run check` — `svelte-check` type-check
