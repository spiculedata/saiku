# Phase 4 — Frontend foundation (implementation plan)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to execute task-by-task.

**Goal:** Modernise the UI's bones without a rewrite. Vite + TypeScript build, AG Grid pivot, Monaco MDX editor, ECharts, dark mode. Bridge phase before the Svelte rewrite in Phase 6.

**Base branch:** `development` (post Phase 3 merge). Note Phase 1 Group A deleted `saiku-ui` entirely; Phase 4 reintroduces a new `saiku-ui/` module scaffolded from scratch with Vite.

**Tech stack:** Vite 5, TypeScript, AG Grid Community (MIT), Monaco Editor, ECharts, Playwright.

**Effort:** 3–4 focused sessions.

---

## Tasks

### Task 4.1 — New `saiku-ui` module with Vite + TypeScript

Scaffold from `npm create vite@latest saiku-ui -- --template vanilla-ts`. Replace vanilla-ts with the minimum of the legacy Backbone app's HTML skeleton to prove the pipeline. Serve dev via `npm run dev`; production `npm run build` emits static assets to `dist/`.

Wire Maven: `saiku-ui/pom.xml` uses `frontend-maven-plugin` to install Node, run `npm ci`, `npm run build`. Output `dist/` embedded in the fat JAR under `classpath:/ui/`.

### Task 4.2 — Shared API client (TypeScript, generated)

Use `openapi-typescript` to generate TS types from Saiku's REST OpenAPI spec (produced in Phase 1 Group C / Phase 6). Single `src/api/client.ts` wraps `fetch`, handles auth, retries.

### Task 4.3 — AG Grid Community pivot grid

Replace the ancient HTML table rendering with AG Grid:
- Install `ag-grid-community` + `ag-grid-vanilla` bindings
- Component `PivotGrid.ts` consumes Saiku's CellSet-JSON (or Arrow when Phase 5 lands)
- Virtualised row model handles 100k+ rows
- Drag-drop from the dim/measure tree onto AG Grid's pivot panel

### Task 4.4 — Monaco editor for MDX + SQL

- Install `monaco-editor`
- Custom language service for MDX:
  - Token highlighting
  - Autocomplete from `/rest/saiku/api/discover/<cube>` (dims, levels, measures)
  - Hover docs
- Same for SQL, but with a simpler completion from `information_schema`

### Task 4.5 — ECharts chart rendering

Replace Highcharts one chart type at a time. Chart types: bar, column, line, area, pie, treemap, heatmap, scatter.

Each migration = Playwright visual-regression test that screenshots the chart pre/post and gates on pixel diff < 1%.

### Task 4.6 — Design tokens + dark mode

`tokens.css` with CSS custom properties for colours, spacing, typography. Dark mode via `[data-theme="dark"]` selector + a toggle in the header. Persist preference to `localStorage`.

### Task 4.7 — Accessibility pass (touched components)

Keyboard navigation on the pivot grid. ARIA labels on drag-drop. Focus management in dialogs. Axe-core lint in CI.

### Task 4.8 — Playwright smoke suite

At least one test per touched screen. Run in CI on every PR. Covers login, open cube, drag dim, see result.

---

## Exit criteria

- `npm run dev` HMR under 200ms
- Grid renders 100k rows without visible lag (< 16ms frame time)
- MDX editor autocomplete working against live metadata
- Highcharts fully removed; all charts on ECharts
- Dark mode shipped
- Lighthouse: > 85 performance, > 90 accessibility on the pivot page
- Playwright suite green in CI

## Risk-ordered task list

| # | Risk | Reversible? | Ship independently? |
|---|------|-------------|---------------------|
| 4.1 Vite/TS scaffold | Low | Yes | Yes |
| 4.2 API client | Low | Yes | Yes |
| 4.3 AG Grid | Med | Yes | Yes |
| 4.4 Monaco | Low | Yes | Yes |
| 4.5 ECharts | Med (visual regressions) | Yes | Per-chart-type |
| 4.6 Design tokens | Low | Yes | Yes |
| 4.7 A11y | Low | Yes | Yes |
| 4.8 Playwright | Low | Yes | Last |
