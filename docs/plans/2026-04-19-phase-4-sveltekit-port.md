# Phase 4 — SvelteKit feature-complete port

> ## ⚠️ STATUS (updated 2026-07-12): effectively complete (~90%+) — this checklist is stale
>
> This doc was written 2026-04-19 as a like-for-like Backbone→Svelte port plan and its
> per-item checkboxes below (which read ~15%) were **never updated** as the work landed. A
> plan-vs-reality inventory found `saiku-ui/src` now ships **158 `.svelte` files + 118 `.ts`
> modules** covering nearly every listed item, plus large subsystems the plan never scoped
> (Dashboards, AI/Ossie query, schema generator, design system + Storybook, share/embed,
> Arrow cellset transport). Treat the checkboxes below as **historical**, not a live TODO.
>
> **Confirmed decisions — WON'T DO (not backlog, deliberate):**
> - **Pivot grid is custom Svelte-native, NOT AG Grid** — `CellsetTable.svelte` + `ossie/pivot.ts`,
>   no `ag-grid` dependency. This is the accepted approach; the AG Grid line in the target stack above
>   is superseded.
> - **Pentaho BIServer integration is dropped** — not ported, and not planned. Any `BIServer` /
>   `Buckets` plugin items below are closed as won't-do.
>
> **Genuinely-remaining backlog (the real Phase-4 tail):**
> - `QueryScenario` — general workspace what-if. (A *scoped* Mondrian what-if shipped for the
>   AQVIRA demo via `/ai/scenario/whatif`, but the general workspace scenario editor is unbuilt.)
> - Anonymous usage-statistics ping — stats *viewing* exists (`StatsAdmin`); the outbound ping does not.
> - Cosmetic "done differently": `SplashScreen` (absorbed into `Skeleton` loaders),
>   `SessionErrorModal` (shipped as a banner), `License` (upgrade banner) — not missing, just different.
>
> Everything else the checklist marks `[ ]` (the ~23 modals, ChartEditor/ECharts, MDX/Monaco,
> AdminConsole, i18n, and the model/store ports) **is built** — see `saiku-ui/src/lib/{views,modals,stores,api}`.

Replaces the original Phase 4 (Vite/TS overlay on Backbone) and Phase 6
(SvelteKit rewrite). The legacy Backbone UI is preserved on disk as
`saiku-ui-legacy/` (restored from commit `4fa20202^`) as a read-only
reference for porting.

Target stack: SvelteKit 2 + Svelte 5 (runes) + TypeScript strict, AG Grid
Community for the pivot grid, Monaco for MDX/SQL editing, ECharts for
charts, `@sveltejs/adapter-static` so the build is a static bundle
served by Saiku's Jetty launcher.

## Slice 1 — Foundation (this commit)

- [x] SvelteKit scaffold (`vite`, `svelte-kit`, `svelte-check`)
- [x] Design tokens (`src/lib/styles/tokens.css`) incl. dark mode
- [x] Session store (`$lib/stores/session.svelte.ts`) + REST client
- [x] Theme store (`$lib/stores/theme.svelte.ts`) with localStorage
- [x] Reusable `Modal.svelte` primitive with backdrop + escape + snippets
- [x] `LoginForm` ported
- [x] `Workspace` shell ported (topbar, sidebar placeholder, tabset)
- [x] Placeholder `AboutModal`

## Legacy view inventory → Svelte status

Source tree: `saiku-ui-legacy/js/saiku/views/`

### Shell

| Legacy view | Status |
|---|---|
| `SplashScreen.js` | [ ] Port as route-level spinner |
| `LoginForm.js` | [x] `src/lib/views/LoginForm.svelte` |
| `DemoLoginForm.js` | [ ] Same form with preset users |
| `SessionErrorModal.js` | [ ] Modal shown on 401/403 |
| `Workspace.js` | [~] `src/lib/views/Workspace.svelte` (shell only) |
| `WorkspaceToolbar.js` | [ ] Query-level toolbar (save, export, MDX, …) |
| `WorkspaceDropZone.js` | [ ] Drag-drop axis wells |
| `Toolbar.js` | [ ] Global toolbar |
| `Tab.js` | [ ] Individual tab |
| `TabSet.js` | [ ] Tab container |
| `Tour.js` | [ ] Interactive tour |
| `Upgrade.js` | [ ] License/version banner |

### Query building

| Legacy view | Status |
|---|---|
| `QueryToolbar.js` | [ ] |
| `Table.js` | [ ] Replace with AG Grid |
| `DimensionList.js` | [ ] Sidebar dimensions + measures tree |
| `ChartEditor.js` | [ ] Chart type/series picker (ECharts) |

### Modals (all require ported dialogs)

| Legacy modal | Status |
|---|---|
| `Modal.js` (base class) | [x] `src/lib/components/Modal.svelte` |
| `AboutModal.js` | [x] Inline in Workspace.svelte |
| `AddFolderModal.js` | [ ] |
| `CalculatedMemberModal.js` | [ ] |
| `CustomFilterModal.js` | [ ] |
| `DataSourcesModal.js` | [ ] |
| `DateFilterModal.js` | [ ] |
| `DeleteRepositoryObject.js` | [ ] |
| `DrillAcrossModal.js` | [ ] |
| `DrillthroughModal.js` | [ ] |
| `FilterModal.js` | [ ] |
| `FormatAsPercentageModal.js` | [ ] |
| `GrowthModal.js` | [ ] |
| `MDXModal.js` | [ ] Monaco-backed |
| `MeasuresModal.js` | [ ] |
| `MoveRepositoryObject.js` | [ ] |
| `OpenDialog.js` | [ ] |
| `OpenQuery.js` | [ ] |
| `OverwriteModal.js` | [ ] |
| `ParentMemberSelectorModal.js` | [ ] |
| `PermissionsModal.js` | [ ] |
| `ReportTitlesModal.js` | [ ] |
| `SaveQuery.js` | [ ] |
| `SelectionsModal.js` | [ ] |
| `WarningModal.js` | [ ] |

### Plugins (modules under `saiku-ui-legacy/js/saiku/plugins/`)

| Plugin | Status |
|---|---|
| `AdminConsole` | [ ] Full admin pane (users, datasources, schemas, logs) |
| `BIServer` | [ ] Pentaho integration — evaluate whether to carry forward |
| `Buckets` | [ ] |
| `CCC_Chart`, `CCC_Chart2` | [ ] Replace with ECharts |
| `ChangeLocale` | [ ] i18n switcher |
| `filters` | [ ] Filter plugin set |
| `Fullscreen` | [ ] Fullscreen toggle |
| `I18n` | [ ] Locale bundle loader |
| `Intro` | [ ] First-run tour |
| `Statistics` | [ ] Anonymous stats ping |

### Models (data layer)

| Legacy model | Svelte equivalent |
|---|---|
| `Session.js` | `$lib/stores/session.svelte.ts` ✅ |
| `SessionWorkspace.js` | [ ] `$lib/stores/workspaces.svelte.ts` |
| `DataSources.js` | [ ] `$lib/api/datasources.ts` + store |
| `Repository.js` | [ ] `$lib/api/repository.ts` + store |
| `Query.js` / `SaikuOlapQuery.js` | [ ] `$lib/api/query.ts` + store |
| `QueryAction.js` | [ ] Async query action builder |
| `QueryScenario.js` | [ ] What-if scenarios |
| `Result.js` | [ ] Row/col cellset type |
| `Dimension.js` / `Level.js` / `Member.js` | [ ] Metadata types |
| `DateFilter.js` | [ ] |
| `License.js` | [ ] |
| `Plugin.js` | [ ] Drop — SvelteKit has no plugin loader here |
| `Settings.js` | [ ] Runtime config (from `/rest/saiku/info`) |

## Slice ordering (follow-ups)

1. **Datasource browser** (`DataSources.js` + `DimensionList.js`) — makes the sidebar do something.
2. **Tabs + save/open cycle** (`TabSet`, `Tab`, `OpenDialog`, `OpenQuery`, `SaveQuery`, `Repository`).
3. **Pivot grid** (AG Grid) + `WorkspaceDropZone` + `QueryToolbar`.
4. **Modal set A** (the small ones): `WarningModal`, `OverwriteModal`, `AddFolderModal`, `DeleteRepositoryObject`, `MoveRepositoryObject`, `AboutModal`, `SessionErrorModal`.
5. **Filter stack**: `FilterModal`, `CustomFilterModal`, `DateFilterModal`, `SelectionsModal`.
6. **MDX + calculated members**: `MDXModal` (Monaco), `CalculatedMemberModal`.
7. **Drill / growth / formatting**: `DrillthroughModal`, `DrillAcrossModal`, `GrowthModal`, `FormatAsPercentageModal`, `ReportTitlesModal`, `ParentMemberSelectorModal`, `MeasuresModal`.
8. **Charts** (`ChartEditor`) — ECharts.
9. **Admin console** (`AdminConsole` plugin) — users, datasources, schemas, logs.
10. **Auxiliary**: `Tour`, `Fullscreen`, `ChangeLocale`, `Upgrade`, `PermissionsModal`, `Statistics`.
11. **Export**: Excel / CSV / PDF endpoints.
12. **`saiku-ui-legacy/` deletion** — after final visual diff + sign-off.

## Integration

- `saiku-launcher` currently serves `/webapp/saiku.war`. Once Slice 1 is
  wired through the maven `frontend-maven-plugin`, the static bundle
  from `saiku-ui/dist` gets copied into the webapp's root so
  `http://localhost:8080/` serves the new UI and `/rest/saiku/*` keeps
  working.
