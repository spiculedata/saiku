# Phase 6 — Svelte rewrite (implementation plan)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to execute task-by-task.

**Goal:** Kill the residual legacy UI from Phase 4. Rewrite the front-end in SvelteKit + TypeScript, preserving parity with the old screens. Bundle < 200KB gz. Accessibility > 95.

**Base branch:** `development` (post Phase 5 merge).

**Tech stack:** SvelteKit 2, TypeScript, Vite (already from Phase 4), `svelte-dnd-action`, Storybook, Playwright.

**Effort:** 4–6 focused sessions, biggest of all phases.

---

## Tasks

### Task 6.1 — SvelteKit scaffold alongside legacy

`saiku-ui-next/` new module. Same Spring Boot app serves both:
- Legacy Phase 4 UI at `/ui/`
- SvelteKit dev+build at `/ui-next/`
- Feature flag `saiku.ui=next` switches the default

### Task 6.2 — Finalise OpenAPI spec

Complete `saiku-core/saiku-web/src/main/resources/openapi.yaml`. Generate TypeScript client via `openapi-typescript-codegen`. Both UIs use the generated client.

### Task 6.3 — Design system `@saiku/ui`

New Svelte component library:
- Tokens from Phase 4 (`tokens.css`)
- Core components: Button, Dialog, Tree, Table, Tabs, Menu, Toast, Spinner
- Storybook catalog with dark-mode toggle
- Published to npm internal registry (or consumed locally via `npm link`)

### Task 6.4 — Screen migration order (low-risk first)

Vertical slices, one per PR:

1. **Login + home** — smallest surface, tests all plumbing (auth, routing, theming).
2. **Datasource admin** — CRUD + list + form validation.
3. **Query workspace** (biggest) — broken into:
   - 6.4.3a Basic drag-drop of dims/measures onto axes
   - 6.4.3b Pivot grid (reuse AG Grid from Phase 4)
   - 6.4.3c Monaco MDX editor tab
   - 6.4.3d Calculated members + filters + drill-through
   - 6.4.3e Query profile drawer
   - 6.4.3f Chart panel
4. **Dashboards** — grid layout + parameters + cross-filter.
5. **Admin / users** — last, touches auth.

### Task 6.5 — Drag-and-drop

`svelte-dnd-action` for the dim/measure → axis drop targets. Keyboard drag-and-drop and screen-reader announcements for free. Playwright a11y test per drop zone.

### Task 6.6 — State management

Svelte stores, no external state lib. Centralise query-workspace state in `queryStore` (derived stores for axes, filters, measures). Time-travel via store snapshots enables undo/redo.

### Task 6.7 — Undo/redo

Keyboard shortcuts ⌘Z / ⌘⇧Z. Persists through the session; not across page reload.

### Task 6.8 — Mobile responsive read-only

Dashboards render on phone with a tap-to-drill gesture. Authoring screens show a "desktop required" notice.

### Task 6.9 — Delete legacy UI

Once the SvelteKit version reaches feature parity:
- Flip default to `saiku.ui=next`
- Remove `/ui/` mount and the Phase 4 `saiku-ui/` module
- Rename `saiku-ui-next/` → `saiku-ui/`
- Big net-negative diff

### Task 6.10 — Pre-release flag

From week 2, ship `saiku serve --ui=next` as an opt-in flag so early adopters can sanity-check. Feedback channel: GitHub Discussions.

---

## Exit criteria

- Every screen present in the old UI has a Svelte equivalent at feature parity
- Playwright suite passes on the new UI at same coverage as the old
- Lighthouse: > 90 performance, > 95 accessibility on the main pages
- Initial route bundle < 200KB gzipped
- Legacy `saiku-ui` module deleted
- Mobile read-only dashboards functional on iOS Safari + Chrome Android

## Risk-ordered task list

| # | Risk | Reversible? | Ship independently? |
|---|------|-------------|---------------------|
| 6.1 Scaffold | Low | Yes | Yes |
| 6.2 OpenAPI | Low | Yes | Yes |
| 6.3 Design system | Low | Yes | Yes |
| 6.4 Screens | Med (UX regressions) | Yes | One PR per screen |
| 6.5 DnD | Low | Yes | Yes |
| 6.6 Stores | Low | Yes | Yes |
| 6.7 Undo/redo | Low | Yes | Yes |
| 6.8 Mobile | Low | Yes | Yes |
| 6.9 Delete legacy | **High** — point of no return | No | Absolute last |
| 6.10 Feature flag | Low | Yes | First |
