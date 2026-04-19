# saiku-ui

Vite + TypeScript + React rebuild of the Saiku UI. Phase 4 slice 1: foundation,
design tokens (light/dark), login page, workspace shell.

## Dev flow

```sh
# Terminal 1 — run the Saiku server (embedded Jetty)
cd ..
mvn -DskipTests -Dmaven.test.skip=true -pl saiku-launcher -am package
java -jar saiku-launcher/target/saiku-3.17.jar serve --port 8080

# Terminal 2 — run the UI in Vite dev mode (proxies /rest/* to :8080)
cd saiku-ui
npm install
npm run dev
# open http://localhost:5173, log in with admin / admin
```

Set `SAIKU_API=http://host:port` before `npm run dev` to point at a different
backend.

## Scripts

- `npm run dev` — Vite dev server with HMR
- `npm run build` — type-check (`tsc --noEmit`) and produce `dist/`
- `npm run preview` — serve the built bundle locally
- `npm run typecheck` — type-check only

## Next slices

- AG Grid workspace pivot grid
- Monaco MDX / SQL editor with discover-backed autocomplete
- ECharts visualisations (one chart type at a time, visual-regression tested)
- Playwright smoke tests per screen
- Maven `frontend-maven-plugin` integration so `mvn package` bundles `dist/`
  into `saiku-webapp`
