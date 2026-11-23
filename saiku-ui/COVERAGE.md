# UI line coverage

From a clean checkout of one revision, install the UI dependencies and run:

```sh
cd saiku-ui
npm ci
npm run coverage:all
```

The commands can also be run separately as `coverage:unit`, `coverage:browser`,
and `coverage:compare`. The normal `npm test` and `npm run e2e` commands do not
collect coverage. `coverage:browser` builds the static app with source maps,
runs the mocked-backend Chromium Playwright specs, and maps V8 script ranges
back to `src/`. A local Chromium installation is required; run
`npx playwright install chromium` if it is missing.

Reports are generated under the ignored `coverage/` directory:

- `unit/lcov.info` — Vitest line coverage.
- `browser/lcov.info` — mocked-browser line coverage.
- `unit/provenance.json` and `browser/provenance.json` — suite, command, source
  revision, and report timestamp.
- `comparison.json` — per-source-line `unit` and `browser` hit counts, including
  lines seen by only one suite. The comparison command rejects reports with
  different Git revisions.

The reports include only loaded or tested source. A missing line or file does
not prove that it is untested, and a zero count means only that its suite did
not execute the instrumented line in that run. Browser coverage covers the
mocked Chromium specs and client JavaScript; it excludes live-backend specs,
server behavior, CSS, and scripts without usable source maps. Source-map
remapping can place bundled code imperfectly at statement boundaries. Compare
positive versus zero counts across suites; the absolute hit counts have
different runtimes and are not directly equivalent.

Run the comparison logic test with `node --test scripts/compare-coverage.test.mjs`.
