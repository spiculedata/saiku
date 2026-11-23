# End-to-end tests

The browser E2E suite is a Playwright project that lives with the UI it drives, in
[`saiku-ui/e2e/`](../../saiku-ui/e2e) (config: [`saiku-ui/playwright.config.ts`](../../saiku-ui/playwright.config.ts)).
This page is the repo-root index so the suite is discoverable from the top level.

| Spec | Mode | What it covers |
|---|---|---|
| `app-builder.spec.ts` | mocked | App Builder empty → built out → persisted flow |
| `ossie-workbench.spec.ts` | mocked | Ossie workbench query flow |
| `app-builder.live.spec.ts` | live | App Builder round-trip against a real launcher |
| `ossie-workbench.live.spec.ts` | live | Ossie workbench against a real launcher |

## Running

```bash
cd saiku-ui
npm install
npx playwright install chromium   # first run only
npm run e2e                       # mocked backend; builds + serves via vite preview on :4173
npm run e2e:ui                    # interactive Playwright UI
```

Mocked specs intercept `/rest/saiku/*` with route handlers, so they need no Java and run in CI
(the `ui` job in `.github/workflows/ci.yml` runs `npm run e2e`).

### Live specs

`*.live.spec.ts` are skipped unless `RUN_LIVE_E2E=1`, so CI never picks them up. They need a
launcher on `:8080` built with the current UI bundle — see the header of
`saiku-ui/e2e/app-builder.live.spec.ts` for the exact build steps (including the mandatory
`mvn -pl saiku-webapp,saiku-launcher clean` before repackaging; stale bundles bite).

```bash
RUN_LIVE_E2E=1 npm run e2e:live
```

Backend contract coverage (REST + real Mondrian/FoodMart values) is not here; it lives in the
`saiku-launcher` failsafe ITs, e.g. `AppBuilderIT`. See `TESTING.md` and `docs/quality.md`.
