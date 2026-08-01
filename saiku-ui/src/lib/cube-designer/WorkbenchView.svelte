<!--
  Step 2 of the cube-authoring flow — the Dimensions Workbench.

  Renders on the same /cubes/new/canvas route (the page chrome,
  tab toggle, and save bar all live on +page.svelte); this file owns
  the three-region body when `store.mode === 'binary'`.

    1. Left rail   — two stacked, independently collapsible panes.
                     Top: "Join groups" — read-only context derived
                     from Step 1's joins (group label + participating
                     tables). Bottom: "Tables" — every on-canvas
                     table, each individually collapsible. Columns
                     inside the tables pane are the only drag sources
                     (payload: `{tableId, columnName, columnType}`).
    2. Center      — two zones stacked: DIMENSIONS (cards w/
                     hierarchies + ordered levels, OR a Mondrian-style
                     nested outline — toggled by a Cards | Tree
                     segmented control) on top, MEASURES (aggregated
                     fact-side columns) below. Drop a column on the
                     dimensions zone to create a new dimension; drop
                     on an existing dimension to append a level; drop
                     on the measures zone to create a sum-by-default
                     measure. Drops work identically in both views.
    3. Right rail  — read-only summary of the joins authored in
                     Step 1. We surface them so the user can see HOW
                     a level reachable via a join is reached, but
                     editing joins stays a Step 1 concern (click
                     "back to Schema Canvas" — i.e. flip the tab).

  Drag-and-drop uses native HTML5 DnD with a single MIME type
  (`application/x-saiku-column`). Drop targets `preventDefault` on
  dragover and read the JSON payload on drop. We don't model
  drag previews or drop validation beyond "is it our payload" — the
  one-MIME / one-shape rule keeps the logic linear.

  Mondrian export wiring lives elsewhere (mondrian-export.ts) — the
  job of THIS file is only to get well-shaped dimensions/measures
  into the store. A follow-up PR will teach the exporter to read
  `store.dimensions` + `store.measures`.
-->
<script lang="ts">
	import {
		Database,
		ChevronRight,
		ChevronDown,
		ChevronUp,
		ChevronLeft,
		GripVertical,
		Plus,
		X,
		Layers,
		Sigma,
		Box,
		Lock,
		PanelRight,
		PanelBottom,
		Copy,
		Hash,
		ListTree,
		Search,
		Pencil,
		Check,
		Maximize2,
		Minimize2,
		ChevronsUpDown,
		KeyRound
	} from 'lucide-svelte';
	import Button from './primitives/button.svelte';
	import ConfirmCubePane from './ConfirmCubePane.svelte';
	import FactsMeasuresPane from './FactsMeasuresPane.svelte';
	import DimensionsHierarchiesPane from './DimensionsHierarchiesPane.svelte';
	import InspectorProperties from './InspectorProperties.svelte';
	import { onMount, untrack } from 'svelte';
	import { SvelteSet } from 'svelte/reactivity';
	import { SchemaCanvasStore, dimKeyIdentity, resolveKeyAttribute } from './state.svelte.js';
	import { getCubeDesignerBackend } from './backend';
	import { mondrianXmlToYaml } from './mondrian-xml-to-yaml';
	import { highlightXml, highlightYaml } from './code-highlight';
	import { exportToMondrianXml } from './mondrian-export';
	import { classifyColumn, isNumericKind } from './workbench-columns';
	import {
		COLUMN_MIME,
		TABLE_MIME,
		JOINGROUP_MIME,
		FACTS_PANE_REORDER_MIME,
		PANE_REORDER_MIME,
		ATTRIBUTE_MIME,
		readColumnPayload,
		readTablePayload,
		readJoinGroupPayload,
		isAnyWorkbenchDrag,
		isPaneReorderDrag,
		isFactsPaneReorderDrag
	} from './workbench-dnd';
	import type {
		ColumnDragPayload,
		TableDragPayload,
		JoinGroupDragPayload,
		AttributeDragPayload
	} from './workbench-dnd';
	import { blankCube, cubeFromDoc, cubeToDoc, renderCalcTokens } from './workbench-cubes';
	import type {
		FactsCalcToken,
		FactsCalcMode,
		FactsCalc,
		FactsMeasureGroup,
		WorkbenchMeasureGroup,
		WorkbenchCube
	} from './workbench-cubes';
	import type {
		SchemaCanvasTable,
		SchemaCanvasColumn,
		SchemaCanvasDimension,
		SchemaCanvasHierarchy,
		SchemaCanvasLevel,
		SchemaCanvasMeasure,
		SourceTableCandidate,
		SchemaCanvasCube
	} from './types.js';

	interface Props {
		store: SchemaCanvasStore;
		/** Has the current schema ever been saved to the gateway. */
		isSchemaSaved?: boolean;
		/** Has the schema been mutated since the last save?  Together
		 *  with `isSchemaSaved`, drives the per-cube rail "Open in Saiku"
		 *  button — greyed out when dirty/unsaved (with a tooltip
		 *  pointing at the top-right Save), enabled when saved+clean. */
		isSchemaDirty?: boolean;
	}

	let { store, isSchemaSaved = false, isSchemaDirty = true }: Props = $props();

	// Injected backend (Sample-data + Try-a-query transport); host-provided.
	const designerBackend = getCubeDesignerBackend();

	// The workbench DnD MIME contract + payload parsers/predicates live in
	// ./workbench-dnd (imported above). The drag *handlers* that mutate
	// component state stay below and reference the imported MIMEs.
	type FactsPaneKind = 'fact' | 'groups' | 'calculations';

	function loadFactsSlots(): FactsPaneKind[] {
		// v2 default — the cube-level 'fact' pane was absorbed into the
		// per-MG editor in the Measure Groups Content column, so it no
		// longer appears as a standalone pane.  Anyone with a persisted
		// shape that still includes 'fact' migrates by dropping it; the
		// per-MG editor takes over its job.
		const fallback: FactsPaneKind[] = ['groups', 'calculations'];
		if (typeof localStorage === 'undefined') return fallback;
		try {
			const raw = localStorage.getItem('saiku.workbench.factsPrototypeState');
			if (!raw) return fallback;
			const obj = JSON.parse(raw) as { slots?: string[] } | null;
			const s = obj?.slots;
			if (Array.isArray(s) && s.length > 0) {
				// Drop legacy 'fact' slot; ensure 'groups' + 'calculations'
				// are both present, preserving user-ordered slots.
				const filtered = s.filter(
					(k): k is FactsPaneKind => k === 'groups' || k === 'calculations'
				);
				const hasGroups = filtered.includes('groups');
				const hasCalcs = filtered.includes('calculations');
				if (hasGroups && hasCalcs && filtered.length === 2) {
					return filtered;
				}
				if (hasGroups && !hasCalcs) {
					return [...filtered, 'calculations'];
				}
				if (!hasGroups && hasCalcs) {
					return ['groups', ...filtered];
				}
			}
		} catch {
			// fall through
		}
		return fallback;
	}
	let factsSlots = $state<FactsPaneKind[]>(loadFactsSlots());
	let factsPaneDragIndex = $state<number | null>(null);
	let factsPaneDragOverIndex = $state<number | null>(null);
	function handleFactsPaneDragStart(e: DragEvent, idx: number) {
		if (!e.dataTransfer) return;
		e.dataTransfer.setData(FACTS_PANE_REORDER_MIME, String(idx));
		e.dataTransfer.effectAllowed = 'move';
		factsPaneDragIndex = idx;
	}
	function handleFactsPaneDragOver(e: DragEvent, idx: number) {
		if (!isFactsPaneReorderDrag(e)) return;
		e.preventDefault();
		if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
		factsPaneDragOverIndex = idx;
	}
	function handleFactsPaneDragLeave(idx: number) {
		if (factsPaneDragOverIndex === idx) factsPaneDragOverIndex = null;
	}
	function handleFactsPaneDrop(e: DragEvent, targetIdx: number) {
		if (!isFactsPaneReorderDrag(e)) return;
		e.preventDefault();
		const raw = e.dataTransfer?.getData(FACTS_PANE_REORDER_MIME) ?? '';
		const srcIdx = Number(raw);
		factsPaneDragIndex = null;
		factsPaneDragOverIndex = null;
		if (!Number.isFinite(srcIdx) || srcIdx === targetIdx) return;
		const next = [...factsSlots];
		const [moved] = next.splice(srcIdx, 1);
		next.splice(targetIdx, 0, moved);
		factsSlots = next;
	}
	function handleFactsPaneDragEnd() {
		factsPaneDragIndex = null;
		factsPaneDragOverIndex = null;
	}

	// ColumnDragPayload / TableDragPayload / JoinGroupDragPayload live in
	// ./workbench-dnd (imported above).

	// classifyColumn / isNumericKind (sqlType → coarse Mondrian kind) live
	// in ./workbench-columns (imported above).

	// ── Left rail: TWO stacked panes ─────────────────────────────────
	// Top:    "Join groups" — read-only context derived from the joins
	//         authored in Step 1. Each group lists the participating
	//         table names so the workbench user can see which tables
	//         hang together without flipping tabs.
	// Bottom: "Tables" — every on-canvas table; columns inside are the
	//         drag sources that feed the dimensions + measures zones.
	// The two sections collapse independently.

	interface JoinGroupRow {
		key: string;
		label: string;
		tableNames: string[];
		tableIds: string[];
	}

	const joinGroupsRail = $derived.by<JoinGroupRow[]>(() => {
		const tableName = (id: string) => store.doc.tables.find((t) => t.id === id)?.name ?? id;
		// Preserve first-seen order so it matches Step 1's panel ordering.
		const order: string[] = [];
		const buckets = new Map<string, { label: string; tableIds: Set<string> }>();
		for (const j of store.doc.joins) {
			const label = store.joinGroupLabelFor(j);
			let entry = buckets.get(label);
			if (!entry) {
				entry = { label, tableIds: new Set() };
				buckets.set(label, entry);
				order.push(label);
			}
			entry.tableIds.add(j.sourceTableId);
			entry.tableIds.add(j.targetTableId);
		}
		return order.map((label) => {
			const entry = buckets.get(label)!;
			const ids = Array.from(entry.tableIds);
			return {
				key: label,
				label,
				tableIds: ids,
				tableNames: ids.map(tableName).sort((a, b) => a.localeCompare(b))
			};
		});
	});

	// Section + row collapse state for the left rail. Sections are
	// independent (collapsing "Join groups" doesn't collapse "Tables").
	let railJoinGroupsCollapsed = $state(false);
	let railTablesCollapsed = $state(false);
	let collapsedJoinGroupRows = $state<Set<string>>(new Set());
	let collapsedTables = $state<Set<string>>(new Set());

	// Free-text filter across table + column names — matched
	// case-insensitively.  Empty string = no filter (show every table
	// collapsed per the default).  When the query is non-empty:
	//   • tables whose name matches are shown (collapsed or not is up
	//     to the user — match isn't enough to auto-expand columns).
	//   • tables with at least one column matching are shown AND
	//     auto-expanded so the matching column is visible.
	let tableFilterQuery = $state('');
	const tableFilterQ = $derived(tableFilterQuery.trim().toLowerCase());
	function tableMatchesFilter(t: SchemaCanvasTable): boolean {
		if (tableFilterQ.length === 0) return true;
		const ident = (t.schema ? `${t.schema}.${t.name}` : t.name).toLowerCase();
		if (ident.includes(tableFilterQ)) return true;
		return t.columns.some((c) => c.name.toLowerCase().includes(tableFilterQ));
	}
	function columnMatchesFilter(colName: string): boolean {
		if (tableFilterQ.length === 0) return true;
		return colName.toLowerCase().includes(tableFilterQ);
	}
	const filteredTables = $derived(store.doc.tables.filter(tableMatchesFilter));
	// Auto-expand any table whose match is via a column (so the match
	// is actually visible without an extra click).  Doesn't toggle
	// `collapsedTables` itself — we just read this Set inside the
	// per-row `isCollapsed` decision so the user's manual collapse
	// state survives clearing the search.
	const forceOpenForFilter = $derived.by(() => {
		const out = new Set<string>();
		if (tableFilterQ.length === 0) return out;
		for (const t of store.doc.tables) {
			if (t.columns.some((c) => c.name.toLowerCase().includes(tableFilterQ))) {
				out.add(t.id);
			}
		}
		return out;
	});

	function toggleJoinGroupRow(key: string) {
		const next = new Set(collapsedJoinGroupRows);
		if (next.has(key)) next.delete(key);
		else next.add(key);
		collapsedJoinGroupRows = next;
	}

	function toggleTableCollapsed(tableId: string) {
		const next = new Set(collapsedTables);
		if (next.has(tableId)) next.delete(tableId);
		else next.add(tableId);
		collapsedTables = next;
	}

	// Default-collapse join-group rows so the pane reads as a compact
	// label list at first glance (mirrors Step 1's joins panel).
	const seenJoinGroupKeys = new Set<string>();
	$effect(() => {
		const next = new Set(collapsedJoinGroupRows);
		let changed = false;
		for (const g of joinGroupsRail) {
			if (!seenJoinGroupKeys.has(g.key)) {
				seenJoinGroupKeys.add(g.key);
				next.add(g.key);
				changed = true;
			}
		}
		if (changed) collapsedJoinGroupRows = next;
	});

	// Default-collapse tables for the same reason — when the user lands
	// on the workbench they should see EVERY on-canvas table as a tight
	// name list, not a wall of columns.  Each table id is tracked once
	// so re-expanding then dropping new tables on the canvas (Step 1)
	// doesn't re-collapse the ones the user already opened.
	const seenTableIds = new Set<string>();
	$effect(() => {
		const next = new Set(collapsedTables);
		let changed = false;
		for (const t of store.doc.tables) {
			if (!seenTableIds.has(t.id)) {
				seenTableIds.add(t.id);
				next.add(t.id);
				changed = true;
			}
		}
		if (changed) collapsedTables = next;
	});

	// ── Center: view toggle (cards ↔ tree ↔ columns) ────────────────
	// Persisted per-user in localStorage so a refresh doesn't reset the
	// analyst's chosen layout.
	//   - cards: dim cards + right Measures rail (legacy)
	//   - tree:  Mondrian-style outline + right Measures rail
	//   - columns: Miller-style swappable panes (the new default; ships
	//     the IA closest to how Saiku consumes a cube)
	// 'facts' used to be a separate view mode; it's now a collapsible
	// section inside Columns, so any persisted 'facts' falls back to
	// 'columns' on load.
	type ViewMode = 'cards' | 'tree' | 'columns';
	const VIEW_MODE_STORAGE_KEY = 'saiku.workbench.viewMode';
	function loadInitialViewMode(): ViewMode {
		if (typeof localStorage === 'undefined') return 'columns';
		const raw = localStorage.getItem(VIEW_MODE_STORAGE_KEY);
		if (raw === 'cards' || raw === 'tree' || raw === 'columns') return raw;
		return 'columns';
	}
	let viewMode = $state<ViewMode>(loadInitialViewMode());
	$effect(() => {
		if (typeof localStorage === 'undefined') return;
		try {
			localStorage.setItem(VIEW_MODE_STORAGE_KEY, viewMode);
		} catch {
			// localStorage may be unavailable (private browsing, quota).
			// Tolerate silently — the in-memory choice still works for
			// the session.
		}
	});

	// ── Columns view: 3 swappable Miller-style panes ────────────────
	// Each slot can render one of a few pane kinds.  Defaults match the
	// common authoring path (dims → drill into hier levels → measures
	// alongside).  Per-user persistence comes later; for now this is
	// component-local so the prototype is reversible.
	// POC pane set, set-theoretically faithful:
	//   - dimensions: list of D (the user's named dim collection)
	//   - attributes: defines A ⊆ T for the selected dim (checkbox UI)
	//   - hierarchies: list of h_j (each a subset of A) for the selected
	//     dim — each row exposes its OWN content drop zone on the right
	//     so the "Hierarchy content" pane folded into this one (the
	//     drag-and-drop target IS the hierarchy row itself).
	type PaneKind = 'dimensions' | 'attributes' | 'hierarchies';
	// Default loadout: the cube tree pane (dims + measures together —
	// Saiku renders them as siblings under the cube, so the authoring
	// surface mirrors that), then a Levels pane to drill into the
	// selected hierarchy, then a Properties inspector.  Variable-length
	// — users add/remove columns from the top "Add column" menu, and
	// each pane has its own delete X.  No hard cap; the layout flexes
	// to fill the width.
	// Fixed 4-pane set: Source / Attributes / Hierarchies / Content.
	// Order is user-controllable via the grip drag-handle on each
	// header; the kind set itself isn't user-mutable (no Add column,
	// no per-pane kind picker, no remove X).  Reorder lives on; the
	// Add-column-menu, the kind dropdown, and the X were dropped once
	// the design locked to four panes.
	let columnSlots = $state<PaneKind[]>(['dimensions', 'attributes', 'hierarchies']);

	// ── Pane drag-to-reorder ────────────────────────────────────────
	// HTML5 DnD with a distinct MIME so it doesn't collide with column /
	// table / join-group drags (those mimes route to handleDragOver +
	// the existing zone handlers).  `paneDragIndex` tracks the source
	// for cursor styling; `paneDragOverIndex` tracks the hover target
	// for the dashed-ring cue.
	let paneDragIndex = $state<number | null>(null);
	let paneDragOverIndex = $state<number | null>(null);

	function handlePaneDragStart(e: DragEvent, idx: number) {
		if (!e.dataTransfer) return;
		e.dataTransfer.setData(PANE_REORDER_MIME, String(idx));
		e.dataTransfer.effectAllowed = 'move';
		paneDragIndex = idx;
	}
	function handlePaneDragOver(e: DragEvent, idx: number) {
		if (!isPaneReorderDrag(e)) return;
		e.preventDefault();
		if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
		paneDragOverIndex = idx;
	}
	function handlePaneDragLeave(idx: number) {
		if (paneDragOverIndex === idx) paneDragOverIndex = null;
	}
	function handlePaneDrop(e: DragEvent, targetIdx: number) {
		if (!isPaneReorderDrag(e)) return;
		e.preventDefault();
		e.stopPropagation();
		const raw = e.dataTransfer?.getData(PANE_REORDER_MIME) ?? '';
		const sourceIdx = raw === '' ? null : Number(raw);
		paneDragIndex = null;
		paneDragOverIndex = null;
		if (sourceIdx === null || !Number.isInteger(sourceIdx) || sourceIdx === targetIdx) return;
		const next = [...columnSlots];
		const [moved] = next.splice(sourceIdx, 1);
		next.splice(targetIdx, 0, moved);
		columnSlots = next;
	}
	function handlePaneDragEnd() {
		paneDragIndex = null;
		paneDragOverIndex = null;
	}
	const PANE_KIND_LABELS: Record<PaneKind, string> = {
		dimensions: 'Dimensions',
		attributes: 'Attributes',
		hierarchies: 'Hierarchies'
	};
	const PANE_KIND_ICONS: Record<PaneKind, typeof Layers> = {
		dimensions: Layers,
		attributes: Database,
		hierarchies: ListTree
	};
	// Cross-pane selection state — clicking an item in one pane lights
	// up the others (Levels reads the selected hierarchy, Properties
	// reads whatever was last clicked).
	let selectedDimensionId = $state<string | null>(null);
	let selectedHierarchyId = $state<string | null>(null);
	let selectedMeasureId = $state<string | null>(null);
	// ── Focus subject — the LAST clicked thing across all panes.  This
	// is what "bright green = actively selected" tracks, and what the
	// Inspector renders.  When focus moves to a hierarchy, the parent
	// dim's row fades from green to grey (context, not active).  Click
	// empty pane background → focusSubject = null, all rows plain. ──
	type FocusKind = 'dim' | 'hierarchy' | 'attribute' | 'measure' | 'mg' | 'cube' | 'calc';
	let focusSubject = $state<{ kind: FocusKind; id: string } | null>(null);
	function focus(kind: FocusKind, id: string) {
		focusSubject = { kind, id };
	}
	function isFocused(kind: FocusKind, id: string | null | undefined): boolean {
		return id != null && focusSubject?.kind === kind && focusSubject.id === id;
	}
	// Grey "last-selected here" — this pane still has a row selected in
	// state, but focus has moved elsewhere.  Used to keep the user's
	// parent-chain visible without competing with the active subject.
	function isContext(kind: FocusKind, id: string | null | undefined): boolean {
		if (id == null) return false;
		if (focusSubject?.kind === kind && focusSubject.id === id) return false;
		if (kind === 'dim') return selectedDimensionId === id;
		if (kind === 'hierarchy') return selectedHierarchyId === id;
		if (kind === 'measure') return selectedMeasureId === id;
		return false;
	}
	// Attributes have no stable id (they're `{tableId, columnName}` pairs
	// inside a dimension's attributes[] array), so the selection key is a
	// composite "<tableId>::<columnName>".  Cleared whenever the active
	// dimension changes.
	let selectedAttributeKey = $state<string | null>(null);
	// Level chip selected in the Hierarchies pane (unique level id) — routes the
	// inspector to the <Level> branch. Cleared alongside selectedAttributeKey.
	let selectedLevelId = $state<string | null>(null);
	// Tracks whether the current `selectedHierarchyId` was set by an
	// explicit user click (`true`) or by the auto-select-first-hierarchy
	// effect below (`false`).  The Inspector reads this to decide whether
	// to show <Hierarchy> props or fall back to <Dimension> props: when a
	// user just clicks a dim row, they expect the dim's properties — not
	// the auto-selected first hierarchy's.  Only an explicit hier click in
	// the Hierarchies pane flips this back to true.
	let hierarchyExplicitlyClicked = $state<boolean>(false);
	// First-visit / cross-tab default selection lives in the per-tab
	// inspector memory block further down (search "Per-tab inspector
	// memory"). That block covers this file's original one-shot "snap to
	// first dim on mount" behaviour AND its F&M equivalent, plus the
	// D&H ↔ F&M snapshot/restore.
	$effect(() => {
		const dim = selectedDimensionId
			? store.dimensions.find((d) => d.id === selectedDimensionId)
			: null;
		if (dim && selectedHierarchyId === null && dim.hierarchies.length > 0) {
			selectedHierarchyId = dim.hierarchies[0].id;
			// Auto-selection — Inspector should still show the dim, not the hier.
			hierarchyExplicitlyClicked = false;
		}
	});
	// Dimensions pane has two modes mirroring the source-picker drawing:
	//   - edit:  source checklist (tables + join groups).  Checking
	//            creates a dim bound to the source; unchecking deletes
	//            the dim.  Includes a per-pane search.
	//   - saved: the numbered list of dims with renamable labels and a
	//            tiny subtitle naming the original source.
	// Defaults to 'saved' when the doc was loaded with existing dims
	// (Step 2 reopened on a cube that's already partly authored) and to
	// 'edit' when nothing's saved yet — so the empty state reads as
	// "pick something" rather than a blank list.
	let dimensionPaneMode = $state<'edit' | 'saved'>(store.dimensions.length > 0 ? 'saved' : 'edit');
	// Which source row in the dim picker is currently expanded to preview its
	// columns/attributes.  One at a time — clicking another row collapses the
	// previous one.  Click on the row body toggles; click on the checkbox is
	// stopped so checking doesn't also toggle the preview.
	let dimensionPreviewKey = $state<string | null>(null);
	let dimensionPaneFilter = $state('');
	// Manual "Add Dimension" form at the bottom of the edit pane.
	// Fast path (per-source +Add dim button) auto-names after the source;
	// this path lets the user type a semantic name up front and pick a
	// source from a dropdown.  Both routes end up calling the same
	// createDimFromTable / createDimFromJoinGroup helpers.
	let manualAddDimOpen = $state(false);
	let manualAddDimName = $state('');
	let manualAddDimSourceKey = $state('');
	function resetManualAddDim(): void {
		manualAddDimOpen = false;
		manualAddDimName = '';
		manualAddDimSourceKey = '';
	}

	// ── Bottom inspector panel (Properties / Code) ──────────────────
	// Resizable + hideable.  Tabs:
	//   - properties: editable fields for whatever the user has
	//     selected (dim / hier).
	//   - code:       Mondrian XML of the whole cube — read-only
	//     "what's getting exported" view.
	// Height + collapsed + active tab persist in localStorage so the
	// analyst's layout sticks across refresh.
	// Inspector tabs are now Properties + Code only.  Sample-data,
	// try-query and cubes-linkage moved to the top-level `Validate` tab
	// (they used to live inside the Inspector as sub-tabs which made the
	// drawer feel like a mini-app).  Legacy persisted values fall back
	// to `properties` in loadInspectorTab.
	type InspectorTab = 'properties' | 'code' | 'sample-data' | 'try-query' | 'cubes';
	const INSPECTOR_TABS: readonly InspectorTab[] = ['properties', 'code'] as const;
	/** Sub-tab within the top-level `Confirm cube` view.  The three
	 *  original tabs (Sample data / Try query / Cubes linkage) used to
	 *  be Inspector tabs.  `canvas` + `tree` were added per #1065 to
	 *  give the cube author a spatial view of what they built and a
	 *  schema-wide outline.  `canvas` is the default landing so a fresh
	 *  Confirm-cube visit shows the shape of the selected cube first. */
	type ValidateTab = 'canvas' | 'sample-data' | 'try-query';
	const VALIDATE_TABS: readonly ValidateTab[] = ['canvas', 'sample-data', 'try-query'];
	// Physical (tables + FK↔PK) vs Semantic (DAG: fact → MG → dim →
	// hierarchies → levels) toggle inside the Confirm cube > Model
	// sub-tab.  Both are lenses on the same cube; the toggle picks
	// which renderer mounts.  Defaults to semantic per Amelia's
	// preference — the semantic view reads as "what the cube IS",
	// physical is the "how it wires up" second look.
	type ModelViewMode = 'semantic' | 'physical';
	const MODEL_VIEW_KEY = 'saiku.workbench.confirmCubeModelView';
	function loadModelView(): ModelViewMode {
		if (typeof window === 'undefined') return 'semantic';
		const v = window.localStorage.getItem(MODEL_VIEW_KEY);
		return v === 'physical' ? 'physical' : 'semantic';
	}
	let modelViewMode = $state<ModelViewMode>(loadModelView());
	// Bump counter for "Reset positions" — one signal watched by whichever
	// renderer (CubeDag / ConfirmCubeCanvas) is mounted in the Model tab.
	let modelResetTs = $state<number>(0);
	// Bump counter for "force re-derive on tab entry" (#1088).  Deep
	// mutations in Facts & Measures (e.g. FK column swap) don't always
	// propagate cleanly through the local `factsMeasureGroups` proxy
	// into the Confirm cube renderer.  Bumping this whenever the user
	// enters the validate view forces every downstream `$derived` /
	// `$effect` that watches it to re-run against the latest MG shape.
	let confirmCubeRefreshTs = $state<number>(0);
	$effect(() => {
		if (store.mode === 'validate') {
			untrack(() => {
				confirmCubeRefreshTs = confirmCubeRefreshTs + 1;
			});
		}
	});
	// Right-panel tree outline state — whole-panel collapse + per-section
	// collapse.  Sections default open; user preference persists per
	// session via localStorage.
	const OUTLINE_COLLAPSED_KEY = 'saiku.workbench.confirmCubeOutlineCollapsed';
	const OUTLINE_SECTIONS_KEY = 'saiku.workbench.confirmCubeOutlineSections';
	function loadOutlineCollapsed(): boolean {
		if (typeof window === 'undefined') return false;
		return window.localStorage.getItem(OUTLINE_COLLAPSED_KEY) === '1';
	}
	function loadOutlineSections(): Set<'facts' | 'mgs' | 'dims'> {
		if (typeof window === 'undefined') return new Set();
		try {
			const raw = window.localStorage.getItem(OUTLINE_SECTIONS_KEY);
			if (!raw) return new Set();
			const arr = JSON.parse(raw) as string[];
			return new Set(
				arr.filter(
					(s): s is 'facts' | 'mgs' | 'dims' => s === 'facts' || s === 'mgs' || s === 'dims'
				)
			);
		} catch {
			return new Set();
		}
	}
	let outlineCollapsed = $state<boolean>(loadOutlineCollapsed());
	let outlineCollapsedSections = $state<Set<'facts' | 'mgs' | 'dims'>>(loadOutlineSections());
	$effect(() => {
		if (typeof window === 'undefined') return;
		try {
			window.localStorage.setItem(OUTLINE_COLLAPSED_KEY, outlineCollapsed ? '1' : '0');
			window.localStorage.setItem(
				OUTLINE_SECTIONS_KEY,
				JSON.stringify([...outlineCollapsedSections])
			);
		} catch {
			// ignore
		}
	});
	function toggleOutlineSection(s: 'facts' | 'mgs' | 'dims'): void {
		const next = new Set(outlineCollapsedSections);
		if (next.has(s)) next.delete(s);
		else next.add(s);
		outlineCollapsedSections = next;
	}
	$effect(() => {
		if (typeof window === 'undefined') return;
		try {
			window.localStorage.setItem(MODEL_VIEW_KEY, modelViewMode);
		} catch {
			// ignore
		}
	});
	const VALIDATE_TAB_KEY = 'saiku.workbench.validateTab';
	function loadValidateTab(): ValidateTab {
		if (typeof window === 'undefined') return 'canvas';
		const v = window.localStorage.getItem(VALIDATE_TAB_KEY);
		return VALIDATE_TABS.includes(v as ValidateTab) ? (v as ValidateTab) : 'canvas';
	}
	let validateTab = $state<ValidateTab>(loadValidateTab());

	// Copy-to-clipboard flash for the inspector code panel.  Flips to
	// true for ~1.2s after a successful write so the button label reads
	// "Copied" as feedback.
	let codeCopyFlash = $state<boolean>(false);
	let codeCopyTimer: ReturnType<typeof setTimeout> | null = null;
	// Code-tab format toggle (#1080): show the schema as Mondrian 4 XML or the
	// equivalent M4 YAML (both from the one XML emitter → no drift).
	let codeFormat = $state<'xml' | 'yaml'>('xml');
	async function copyInspectorCode(xml: string) {
		try {
			await navigator.clipboard.writeText(xml);
			codeCopyFlash = true;
			if (codeCopyTimer) clearTimeout(codeCopyTimer);
			codeCopyTimer = setTimeout(() => {
				codeCopyFlash = false;
			}, 1200);
		} catch {
			// Silent — clipboard write can reject on non-secure contexts.
		}
	}

	// Cube-panel local UI state.  Draft only — permissions aren't wired
	// to the backend yet.  Structure mirrors the schema ACL (workspace-
	// role grants + a visibility toggle) so this maps 1:1 when the API
	// lands.  See #1029 for the underlying cube ACL work.
	let cubePermissionsOpen = $state<boolean>(false);
	let cubeNewFormOpen = $state<boolean>(false);
	let cubeNewFormName = $state<string>('');
	let cubeNewFormVisibility = $state<'private' | 'workspace' | 'public'>('workspace');
	let cubeNewFormGroups = $state<string>('');
	$effect(() => {
		if (typeof window !== 'undefined') {
			localStorage.setItem(VALIDATE_TAB_KEY, validateTab);
		}
	});
	// Columns view layout — two stacked sections (Dimensions + Facts) on
	// the left, an Inspector drawer on the right.  Each main section
	// collapses to a strip via its chevron; the drawer collapses to a
	// thin vertical strip.  Fractions/widths persist so the layout
	// sticks across refresh.
	const DIM_FACTS_FRACTION_KEY = 'saiku.workbench.dimFactsFraction';
	const DIM_SECTION_COLLAPSED_KEY = 'saiku.workbench.dimCollapsed';
	const FACTS_SECTION_COLLAPSED_KEY = 'saiku.workbench.factsCollapsed';
	const INSPECTOR_DRAWER_WIDTH_KEY = 'saiku.workbench.inspectorDrawerWidth';
	const INSPECTOR_COLLAPSED_KEY = 'saiku.workbench.inspectorCollapsed';
	const INSPECTOR_TAB_KEY = 'saiku.workbench.inspectorTab';
	function loadDimFactsFraction(): number {
		const DEFAULT = 0.65; // Dimensions gets the lion's share by default
		if (typeof localStorage === 'undefined') return DEFAULT;
		const raw = localStorage.getItem(DIM_FACTS_FRACTION_KEY);
		const n = raw ? Number(raw) : NaN;
		return Number.isFinite(n) && n >= 0.1 && n <= 0.9 ? n : DEFAULT;
	}
	function loadBoolLS(key: string, fallback: boolean): boolean {
		if (typeof localStorage === 'undefined') return fallback;
		const raw = localStorage.getItem(key);
		if (raw === 'true') return true;
		if (raw === 'false') return false;
		return fallback;
	}
	function loadInspectorDrawerWidth(): number {
		const DEFAULT = 400;
		if (typeof localStorage === 'undefined') return DEFAULT;
		const raw = localStorage.getItem(INSPECTOR_DRAWER_WIDTH_KEY);
		const n = raw ? Number(raw) : NaN;
		return Number.isFinite(n) && n >= 240 && n <= 4000 ? n : DEFAULT;
	}
	// Inspector moved from the RIGHT-hand column to a BOTTOM row.  Height
	// takes over the role width used to play.  Range guarded so the top
	// panes always keep at least ~200px of vertical room.
	const INSPECTOR_DRAWER_HEIGHT_KEY = 'saiku.workbench.inspectorDrawerHeight';
	function loadInspectorDrawerHeight(): number {
		const DEFAULT = 280;
		if (typeof localStorage === 'undefined') return DEFAULT;
		const raw = localStorage.getItem(INSPECTOR_DRAWER_HEIGHT_KEY);
		const n = raw ? Number(raw) : NaN;
		// Runtime cap is dynamic (viewport-relative); persistence just
		// enforces sanity (positive + not absurd).
		return Number.isFinite(n) && n >= 160 && n <= 4000 ? n : DEFAULT;
	}
	function loadInspectorTab(): InspectorTab {
		if (typeof localStorage === 'undefined') return 'properties';
		const v = localStorage.getItem(INSPECTOR_TAB_KEY);
		return INSPECTOR_TABS.includes(v as InspectorTab) ? (v as InspectorTab) : 'properties';
	}
	let dimFactsFraction = $state<number>(loadDimFactsFraction());
	// Defaults: BOTH stacked sections (Dimensions + Facts/Measures) expanded
	// at roughly half height each, and the Inspector drawer visible — first
	// landing should already show the workbench's full surface area so the
	// user doesn't have to hunt for the panes that matter.  Returning users
	// retain whatever they last toggled (loadBoolLS reads localStorage first).
	let dimSectionCollapsed = $state<boolean>(loadBoolLS(DIM_SECTION_COLLAPSED_KEY, false));
	let factsSectionCollapsed = $state<boolean>(loadBoolLS(FACTS_SECTION_COLLAPSED_KEY, false));
	let inspectorCollapsed = $state<boolean>(loadBoolLS(INSPECTOR_COLLAPSED_KEY, false));
	let inspectorDrawerWidth = $state<number>(loadInspectorDrawerWidth());
	let inspectorDrawerHeight = $state<number>(loadInspectorDrawerHeight());
	// Inspector docking side — user's choice.  Mirrors the "docked to
	// side / bottom" toggle DevTools has.  Persisted so it survives
	// reloads.  Chevrons + resize handles + collapsed strip flip
	// orientation based on this.
	const INSPECTOR_POSITION_KEY = 'saiku.workbench.inspectorPosition';
	type InspectorPosition = 'bottom' | 'right';
	function loadInspectorPosition(): InspectorPosition {
		if (typeof localStorage === 'undefined') return 'bottom';
		const v = localStorage.getItem(INSPECTOR_POSITION_KEY);
		return v === 'right' || v === 'bottom' ? v : 'bottom';
	}
	let inspectorPosition = $state<InspectorPosition>(loadInspectorPosition());
	$effect(() => {
		if (typeof localStorage !== 'undefined') {
			localStorage.setItem(INSPECTOR_POSITION_KEY, inspectorPosition);
		}
	});
	$effect(() => {
		// Both stacked sections can't be collapsed simultaneously — leave
		// at least one expanded so the main area isn't empty.
		if (dimSectionCollapsed && factsSectionCollapsed) {
			factsSectionCollapsed = false;
		}
	});
	// Top-level tab selection drives which stacked section is visible:
	// `workbench` → dim visible, facts collapsed; `facts` → the reverse.
	// The collapse buttons stay hidden in these modes so the user picks
	// tabs at the top, not stack-collapse chevrons inside the view.
	$effect(() => {
		if (store.mode === 'workbench') {
			dimSectionCollapsed = false;
			factsSectionCollapsed = true;
		} else if (store.mode === 'facts') {
			dimSectionCollapsed = true;
			factsSectionCollapsed = false;
		}
	});
	let inspectorTab = $state<InspectorTab>(loadInspectorTab());
	// Maximize the Inspector drawer to cover the whole workbench body so
	// the Sample-data table / Try-a-query result has room to breathe.
	// Session-only — purposely doesn't persist; the user opts in each
	// time they want to drill into the inspector's content.
	let inspectorMaximized = $state<boolean>(false);
	let workbenchBodyEl = $state<HTMLDivElement | null>(null);
	let inspectorDrawerEl = $state<HTMLDivElement | null>(null);

	// ── Facts-mode prototype state ────────────────────────────────────
	// Visual sketch only — not wired to the doc, but persisted in
	// localStorage so the chosen fact table, measures, groups and
	// calculations survive reloads (the dim side persists via the
	// store; the facts prototype persists here).
	// Editorial: calculated measures will probably move to a
	// cube-level (or workspace-level) catalog one day rather than live
	// nested inside a measure group.  Mondrian allows both; we'll keep
	// them nested for the prototype since that maps 1-1 to Amelia's
	// sketch, but the helpers below are pure so the eventual lift is
	// just relocating storage.
	//
	// A calculation has two builder modes the user can flip between:
	//   - `build`      — drag/click chips: alternating measure / op
	//                    tokens that always stay well-formed.
	//   - `expression` — free-text formula for anything the chip
	//                    builder can't express (CASE, IIF, ratios with
	//                    constants, etc.).
	// Switching build→expression seeds the textarea with the rendered
	// chip formula so nothing is lost.  Switching back warns first.
	// FactsCalcToken / FactsCalcMode / FactsCalc / FactsMeasureGroup and the
	// WorkbenchCube model live in ./workbench-cubes (imported above).
	const FACTS_STATE_KEY = 'saiku.workbench.factsPrototypeState';
	type FactsPersistedState = {
		editMode: boolean;
		selectedTableId: string | null;
		tableConfirmed: boolean;
		selectedMeasures: string[];
		tableSearch: string;
		measureGroups: FactsMeasureGroup[];
		selectedGroupId: string | null;
		groupsEditMode: boolean;
		// Cube-scope calc storage — Mondrian 4 puts <CalculatedMember>
		// at <Cube>, not nested under <MeasureGroup>, so a calc can
		// reference any measure on any group.  Pre-refactor persisted
		// states had calcs nested in each measure group; loadFactsState
		// migrates them up into this flat array.
		factsCalcs: FactsCalc[];
		selectedCalcId: string | null;
		calcsEditMode: boolean;
		slots: FactsPaneKind[];
		groupSeq: number;
		calcSeq: number;
	};
	// Feature flag — drag-a-measure-group → calc formula drop.  When the
	// user dropped a group onto a calc, every measure in the group was
	// pushed as a `+`-joined chip sequence.  Powerful for sum-of-group
	// calcs, but it caught newer users out — one drop suddenly produced
	// 4–8 chips and a formula they didn't ask for.  Off by default; flip
	// to `true` here to re-enable.  No user-visible toggle yet — see
	// saiku-cloud#965 for the eventual UI when the workbench has a
	// settings drawer to host it.
	const ALLOW_GROUP_DROP_ON_CALC = false;
	// Legacy persisted shape we still need to read on first load to
	// migrate calcs out of measure groups.  Newer writes never include
	// `calculations` on a group.
	type LegacyFactsMeasureGroup = FactsMeasureGroup & {
		calculations?: FactsCalc[];
		measureCaptions?: Record<string, string>;
	};
	function loadFactsState(): Partial<FactsPersistedState> {
		if (typeof localStorage === 'undefined') return {};
		try {
			const raw = localStorage.getItem(FACTS_STATE_KEY);
			if (!raw) return {};
			const parsed = JSON.parse(raw) as Partial<
				Omit<FactsPersistedState, 'measureGroups'> & {
					measureGroups: LegacyFactsMeasureGroup[];
				}
			>;
			// Splat any legacy in-group calcs up to the cube-scope array
			// and strip them from the groups.  Also ensure each calc has
			// the `tokens` array (legacy used `formula`).
			const migratedCalcs: FactsCalc[] = Array.isArray(parsed.factsCalcs) ? parsed.factsCalcs : [];
			if (Array.isArray(parsed.measureGroups)) {
				for (const g of parsed.measureGroups) {
					if (!Array.isArray(g.measureColumns)) g.measureColumns = [];
					if (!g.measureCaptions || typeof g.measureCaptions !== 'object') {
						g.measureCaptions = {};
					}
					if (Array.isArray(g.calculations)) {
						for (const c of g.calculations) migratedCalcs.push(c);
						delete g.calculations;
					}
				}
			}
			for (const c of migratedCalcs) {
				if (!Array.isArray(c.tokens)) c.tokens = [];
			}
			const result: Partial<FactsPersistedState> = {
				...parsed,
				factsCalcs: migratedCalcs,
				measureGroups: parsed.measureGroups as FactsMeasureGroup[] | undefined
			};
			return result;
		} catch {
			return {};
		}
	}
	// ── Cubes (Mondrian 4) ────────────────────────────────────────────
	// A schema can declare multiple cubes; each cube owns its measure
	// groups, dim-link mapping (which dimensions the cube binds + via
	// which FK column on the fact table), and calculated members.  v1
	// keeps the workbench pane layout single-cube — the selected cube's
	// state hydrates the existing `factsX` live variables via switchCube.
	// Per-MG `factTableId` lets Mondrian 4's "different MG, different
	// fact table" pattern be modelled even if the v1 UI defaults all MGs
	// in a cube to the cube's currently-picked fact.
	// MeasureGroupDimLink / WorkbenchMeasureGroup / WorkbenchCube live in
	// ./workbench-cubes (imported above) so the doc⇄workbench mapping is
	// unit-testable outside a Svelte render harness.
	type CubesPersistedState = {
		cubes: WorkbenchCube[];
		selectedCubeId: string;
	};
	function loadCubesState(): CubesPersistedState {
		// The durable cube model now lives on the doc — the store migrates
		// any legacy `saiku.workbench.cubesState` into `doc.cubes` on load,
		// so hydrating from the doc is enough for external (DimSum / import)
		// writes to appear on a WorkbenchView remount.
		const docCubes = store.cubes;
		if (docCubes.length > 0) {
			const cubes = docCubes.map(cubeFromDoc);
			return { cubes, selectedCubeId: cubes[0].id };
		}
		// Legacy fallback: the even-older single-cube facts prototype under
		// FACTS_STATE_KEY.  Wrap it into a freshly-named "Cube 1".
		const legacy = loadFactsState();
		const cube: WorkbenchCube = {
			...blankCube('cube-1', 'Cube 1'),
			editMode: legacy.editMode ?? true,
			selectedTableId: legacy.selectedTableId ?? null,
			tableConfirmed: legacy.tableConfirmed ?? false,
			selectedMeasures: legacy.selectedMeasures ?? [],
			tableSearch: legacy.tableSearch ?? '',
			measureGroups: (legacy.measureGroups ?? []).map((g) => ({
				...g,
				factTableId: legacy.selectedTableId ?? null,
				dimensionLinks: []
			})),
			selectedGroupId: legacy.selectedGroupId ?? null,
			groupsEditMode: legacy.groupsEditMode ?? true,
			groupSeq: legacy.groupSeq ?? 0,
			factsCalcs: legacy.factsCalcs ?? [],
			selectedCalcId: legacy.selectedCalcId ?? null,
			calcsEditMode: legacy.calcsEditMode ?? true,
			calcSeq: legacy.calcSeq ?? 0
		};
		return { cubes: [cube], selectedCubeId: cube.id };
	}
	const _cubesInit = loadCubesState();
	let cubes = $state<WorkbenchCube[]>(_cubesInit.cubes);
	// Prefer the store's active cube so the selection SURVIVES the
	// DimSum-triggered remount (workbenchKey bump); fall back to the loaded
	// default. store.activeCubeId is kept in sync by the effect below.
	const _resolvedSelectedCubeId =
		store.activeCubeId && _cubesInit.cubes.some((c) => c.id === store.activeCubeId)
			? store.activeCubeId
			: _cubesInit.selectedCubeId;
	let selectedCubeId = $state<string>(_resolvedSelectedCubeId);
	// Guard the persist effect against spurious writes: only push to
	// `store.setCubes` when the trimmed doc shape actually changed (UI-only
	// edits like search text / edit-mode toggles must NOT dirty the doc).
	// Seeded with the current mapping so the first post-mount run is a no-op.
	let lastPersistedCubesJson = JSON.stringify(_cubesInit.cubes.map(cubeToDoc));
	// Mirror the selected cube into the store: keeps it stable across remounts
	// and lets the DimSum chat (SchemaCanvasView) reset when the cube changes.
	$effect(() => {
		store.activeCubeId = selectedCubeId;
	});
	const _liveCubeInit =
		_cubesInit.cubes.find((c) => c.id === _resolvedSelectedCubeId) ?? _cubesInit.cubes[0];
	// _factsInit kept under the same name so the existing factsX state
	// initializers below don't need to change.  Populated from the live
	// cube's snapshot.  Any future cube-level field that wants to wire
	// into the live state just adds itself here.
	const _factsInit: Partial<FactsPersistedState> = {
		editMode: _liveCubeInit.editMode,
		selectedTableId: _liveCubeInit.selectedTableId,
		tableConfirmed: _liveCubeInit.tableConfirmed,
		selectedMeasures: _liveCubeInit.selectedMeasures,
		tableSearch: _liveCubeInit.tableSearch,
		measureGroups: _liveCubeInit.measureGroups as FactsMeasureGroup[],
		selectedGroupId: _liveCubeInit.selectedGroupId,
		groupsEditMode: _liveCubeInit.groupsEditMode,
		factsCalcs: _liveCubeInit.factsCalcs,
		selectedCalcId: _liveCubeInit.selectedCalcId,
		calcsEditMode: _liveCubeInit.calcsEditMode,
		groupSeq: _liveCubeInit.groupSeq,
		calcSeq: _liveCubeInit.calcSeq
	};
	let factsEditMode = $state<boolean>(_factsInit.editMode ?? true);
	let factsSelectedTableId = $state<string | null>(_factsInit.selectedTableId ?? null);
	let factsTableConfirmed = $state<boolean>(_factsInit.tableConfirmed ?? false);
	// When no fact has been picked yet — OR the previously picked table got
	// removed from the canvas — make sure the picker list is the visible
	// branch in the Facts & Measures pane.  Three things can leave us
	// looking at a silently empty pane:
	//   1. factsSelectedTableId is null (fresh workbench)
	//   2. factsSelectedTableId points to a table no longer on the canvas
	//      (user removed it via the schema canvas)
	//   3. factsTableConfirmed is stale-true from a previous session even
	//      though no table is actually selected
	// All three reset to the same "show the picker" state here.
	$effect(() => {
		const stillOnCanvas = factsSelectedTableId
			? store.doc.tables.some((t) => t.id === factsSelectedTableId)
			: false;
		if (!stillOnCanvas) {
			if (factsSelectedTableId !== null) factsSelectedTableId = null;
			if (!factsEditMode) factsEditMode = true;
			if (factsTableConfirmed) factsTableConfirmed = false;
		}
	});
	const factsSelectedMeasures = new SvelteSet<string>(_factsInit.selectedMeasures ?? []);
	let factsTableSearch = $state<string>(_factsInit.tableSearch ?? '');

	// Measure groups — the fact-side analogue of hierarchies.  A group
	// is a named collection of measures (same fact table).  Two-column
	// body that exactly mirrors the dim Hierarchies pane:
	// Name list | Content (included measures).  Calculated measures
	// live at cube scope in their own pane (factsCalcs).
	let factsMeasureGroups = $state<WorkbenchMeasureGroup[]>(
		(_factsInit.measureGroups ?? []) as WorkbenchMeasureGroup[]
	);
	let factsSelectedGroupId = $state<string | null>(_factsInit.selectedGroupId ?? null);
	let factsGroupsEditMode = $state<boolean>(_factsInit.groupsEditMode ?? true);
	let factsGroupSeq = _factsInit.groupSeq ?? 0;
	function nextFactsGroupName(): string {
		const used = new Set(factsMeasureGroups.map((g) => g.name));
		let i = factsMeasureGroups.length + 1;
		while (used.has(`Group ${i}`)) i++;
		return `Group ${i}`;
	}
	function addFactsMeasureGroup() {
		const g: FactsMeasureGroup = {
			id: `mg-${++factsGroupSeq}`,
			name: nextFactsGroupName(),
			measureColumns: []
		};
		factsMeasureGroups.push(g);
		factsSelectedGroupId = g.id;
		focusGroupId = g.id;
		factsEditingMeasureGroupId = g.id;
	}
	function removeFactsMeasureGroup(id: string) {
		factsMeasureGroups = factsMeasureGroups.filter((g) => g.id !== id);
		if (factsSelectedGroupId === id) factsSelectedGroupId = null;
	}
	function renameFactsMeasureGroup(id: string, name: string) {
		const g = factsMeasureGroups.find((x) => x.id === id);
		if (g) g.name = name;
	}

	// Calculated measures live at CUBE scope (Mondrian 4 puts
	// <CalculatedMember> directly under <Cube>, alongside
	// <MeasureGroups>).  A calc can reference any measure on any group.
	// Flat array; helpers take (calcId, …) only.
	let factsCalcs = $state<FactsCalc[]>(_factsInit.factsCalcs ?? []);
	let factsSelectedCalcId = $state<string | null>(_factsInit.selectedCalcId ?? null);
	let factsCalcsEditMode = $state<boolean>(_factsInit.calcsEditMode ?? true);
	// Per-calc edit toggle.  null = no calc in edit mode.  Replaces
	// the old pane-wide factsCalcsEditMode for the row-level controls
	// (rename input, build/expression toggle, remove X) so the user
	// can sit in read-mode on every calc except the one they're
	// actively shaping.
	let factsEditingCalcId = $state<string | null>(null);
	let factsCalcSeq = _factsInit.calcSeq ?? 0;

	// ── Cube CRUD + live-state swap buffer ───────────────────────────
	// Switching cubes snapshots the leaving cube's live state into the
	// cubes[] entry and then hydrates the entering cube's state back into
	// the live `factsX` variables.  Persist effect (further down) maps the
	// live state into the selected cube and writes the trimmed cube list to
	// the doc via `store.setCubes` on every change.
	let cubeSeq = (() => {
		let max = 0;
		for (const c of cubes) {
			const m = /cube-(\d+)/.exec(c.id);
			if (m) max = Math.max(max, parseInt(m[1], 10));
		}
		return max;
	})();
	const selectedCube = $derived(cubes.find((c) => c.id === selectedCubeId) ?? cubes[0]);

	// Rewrite `linkKind` on every MG dim link so degenerate dims (dim
	// source table === MG's fact table) get 'fact' and everything else
	// gets 'foreign-key'.  Called ONE-SHOT on hydrate/import (below) and
	// on the checkbox handler — NOT as a reactive $effect, because deep
	// reactivity on `factsMeasureGroups` + mutation-inside-effect can
	// starve the UI on large schemas.
	onMount(() => {
		normalizeDegenerateLinks(factsMeasureGroups);
		for (const c of cubes) normalizeDegenerateLinks(c.measureGroups ?? []);
	});
	function normalizeDegenerateLinks(mgs: WorkbenchMeasureGroup[]): void {
		const tableById = new Map(store.doc.tables.map((t) => [t.id, t]));
		const dimById = new Map(store.dimensions.map((d) => [d.id, d]));
		for (const mg of mgs) {
			if (!mg.dimensionLinks) continue;
			const fact = mg.factTableId ? tableById.get(mg.factTableId) : null;
			for (const link of mg.dimensionLinks) {
				const dim = dimById.get(link.dimensionId);
				if (!dim) continue;
				const dimSrc = dim.sourceTableId ?? dim.primaryKeyTableId;
				const isDegenerate = !!fact && !!dimSrc && dimSrc === fact.id;
				if (isDegenerate && link.linkKind !== 'fact') {
					link.linkKind = 'fact';
					if (link.foreignKeyColumn) link.foreignKeyColumn = '';
				} else if (!isDegenerate && link.linkKind === 'fact') {
					link.linkKind = 'foreign-key';
				}
			}
		}
	}
	function snapshotLiveIntoCube(target: WorkbenchCube): void {
		target.editMode = factsEditMode;
		target.selectedTableId = factsSelectedTableId;
		target.tableConfirmed = factsTableConfirmed;
		target.selectedMeasures = [...factsSelectedMeasures];
		target.tableSearch = factsTableSearch;
		target.measureGroups = $state.snapshot(factsMeasureGroups) as WorkbenchMeasureGroup[];
		target.selectedGroupId = factsSelectedGroupId;
		target.groupsEditMode = factsGroupsEditMode;
		target.groupSeq = factsGroupSeq;
		target.factsCalcs = $state.snapshot(factsCalcs) as FactsCalc[];
		target.selectedCalcId = factsSelectedCalcId;
		target.calcsEditMode = factsCalcsEditMode;
		target.calcSeq = factsCalcSeq;
	}
	/** The selected cube's in-flight (unsaved) `factsX` state as a plain
	 *  snapshot object — the overlay applied to the selected cube before it
	 *  is mapped to the durable doc shape. */
	function liveCubeSnapshot() {
		return {
			editMode: factsEditMode,
			selectedTableId: factsSelectedTableId,
			tableConfirmed: factsTableConfirmed,
			selectedMeasures: [...factsSelectedMeasures],
			tableSearch: factsTableSearch,
			measureGroups: $state.snapshot(factsMeasureGroups) as WorkbenchMeasureGroup[],
			selectedGroupId: factsSelectedGroupId,
			groupsEditMode: factsGroupsEditMode,
			groupSeq: factsGroupSeq,
			factsCalcs: $state.snapshot(factsCalcs) as FactsCalc[],
			selectedCalcId: factsSelectedCalcId,
			calcsEditMode: factsCalcsEditMode,
			calcSeq: factsCalcSeq
		};
	}
	/** The doc-shape cube list reflecting live (unsaved) edits — the SINGLE
	 *  source of truth the persist path AND the inspector Code-tab preview
	 *  both feed through `exportToMondrianXml`, so the previewed Mondrian 4
	 *  XML is byte-identical to what a save/export emits (#1038). */
	function liveDocCubes(): SchemaCanvasCube[] {
		const snap = liveCubeSnapshot();
		const liveCubes = ($state.snapshot(cubes) as WorkbenchCube[]).map((c) =>
			c.id === selectedCubeId ? { ...c, ...snap } : c
		);
		return liveCubes.map(cubeToDoc);
	}
	/** Read-only Mondrian 4 XML for the inspector Code tab.  Runs the live
	 *  workbench state through the CANONICAL exporter (`exportToMondrianXml`)
	 *  — the same emitter the save/export path uses — so preview == export.
	 *  Degrades to a comment (never throws) while the schema is incomplete
	 *  (e.g. no fact table yet), the one state where the exporter throws. */
	function workbenchPreviewXml(): string {
		try {
			return exportToMondrianXml({ ...store.doc, cubes: liveDocCubes() });
		} catch (e) {
			const msg = e instanceof Error ? e.message : 'Nothing to preview yet';
			return `<?xml version="1.0" encoding="UTF-8"?>\n<!-- ${msg} -->`;
		}
	}
	function hydrateLiveFromCube(source: WorkbenchCube): void {
		factsEditMode = source.editMode;
		factsSelectedTableId = source.selectedTableId;
		factsTableConfirmed = source.tableConfirmed;
		factsSelectedMeasures.clear();
		for (const m of source.selectedMeasures) factsSelectedMeasures.add(m);
		factsTableSearch = source.tableSearch;
		factsMeasureGroups = source.measureGroups;
		factsSelectedGroupId = source.selectedGroupId;
		factsGroupsEditMode = source.groupsEditMode;
		factsGroupSeq = source.groupSeq;
		factsCalcs = source.factsCalcs;
		factsSelectedCalcId = source.selectedCalcId;
		factsCalcsEditMode = source.calcsEditMode;
		factsCalcSeq = source.calcSeq;
		normalizeDegenerateLinks(factsMeasureGroups);
	}
	function switchCube(targetId: string): void {
		if (targetId === selectedCubeId) return;
		const target = cubes.find((c) => c.id === targetId);
		if (!target) return;
		const leaving = cubes.find((c) => c.id === selectedCubeId);
		if (leaving) snapshotLiveIntoCube(leaving);
		hydrateLiveFromCube(target);
		selectedCubeId = targetId;
	}
	function nextCubeName(): string {
		const used = new Set(cubes.map((c) => c.name));
		let i = cubes.length + 1;
		while (used.has(`Cube ${i}`)) i++;
		return `Cube ${i}`;
	}
	// UI state for the header cube switcher + inspector Cubes tab.
	let cubeMenuOpen = $state<boolean>(false);
	function addCube(): WorkbenchCube {
		// Snapshot current cube before adding so live state isn't lost.
		const leaving = cubes.find((c) => c.id === selectedCubeId);
		if (leaving) snapshotLiveIntoCube(leaving);
		const fresh = blankCube(`cube-${++cubeSeq}`, nextCubeName());
		cubes.push(fresh);
		hydrateLiveFromCube(fresh);
		selectedCubeId = fresh.id;
		return fresh;
	}
	function renameCube(id: string, name: string): void {
		const c = cubes.find((x) => x.id === id);
		if (c) c.name = name;
	}
	function addFactsCalc() {
		const c: FactsCalc = {
			id: `calc-${++factsCalcSeq}`,
			name: '',
			tokens: []
		};
		factsCalcs.push(c);
		factsSelectedCalcId = c.id;
		// New calcs land in edit mode so the user can type immediately.
		factsEditingCalcId = c.id;
	}

	// Backfill factTableId on legacy MGs.  Looks at BOTH the canvas table
	// list (store.doc.tables) AND the full source catalog (store.sourceTables)
	// since legacy MGs often reference columns on a source table that
	// isn't on the canvas yet.  When the unique match is source-only, the
	// source table gets auto-added to the canvas at a free corner so the
	// picker (which reads canvas tables) actually has it.
	$effect(() => {
		if (store.doc.tables.length === 0 && store.sourceTables.length === 0) return;
		const matchesAll = (cols: string[], colSet: Set<string>) => cols.every((c) => colSet.has(c));
		const inferOnMG = (mg: WorkbenchMeasureGroup) => {
			if (mg.factTableId || mg.measureColumns.length === 0) return;
			// First try canvas tables — fast path, no side-effects.
			const onCanvas = store.doc.tables.filter((t) => {
				const cols = new Set(t.columns.map((c) => c.name));
				return matchesAll(mg.measureColumns, cols);
			});
			if (onCanvas.length === 1) {
				mg.factTableId = onCanvas[0].id;
				return;
			}
			if (onCanvas.length > 1) return; // ambiguous — let the user pick
			// Fall through to the full source catalog.
			const inSource = store.sourceTables.filter((t) => {
				const cols = new Set(t.columns.map((c) => c.name));
				return matchesAll(mg.measureColumns, cols);
			});
			if (inSource.length !== 1) return;
			const cand = inSource[0];
			// Auto-add at the bottom so an arranged canvas isn't blown up.
			const maxY = store.doc.tables.reduce((m, t) => Math.max(m, t.position.y), 0);
			const fresh = store.addTable(cand, { x: 40, y: maxY + 240 });
			mg.factTableId = fresh.id;
		};
		for (const mg of factsMeasureGroups) inferOnMG(mg);
		for (const cube of cubes) {
			if (cube.id === selectedCubeId) continue;
			for (const mg of cube.measureGroups) inferOnMG(mg);
		}
	});
	// Bind a measure group to a source-catalog table.  The canvas is just
	// the visual model — the SOURCE OF TRUTH for "what tables/columns
	// exist" is `store.sourceTables` (the full source catalog).  If the
	// picked source table isn't on the canvas yet, we add it transparently
	// so factTableId (which references a canvas-id) has something to point
	// at.  Measures already picked on the MG are filtered to those that
	// still exist on the new fact.
	function bindMGToSourceTable(mg: WorkbenchMeasureGroup, cand: SourceTableCandidate) {
		const existing = store.doc.tables.find(
			(t) => t.name === cand.name && (t.schema ?? null) === (cand.schema ?? null)
		);
		let tableId: string;
		let columnNames: Set<string>;
		if (existing) {
			tableId = existing.id;
			columnNames = new Set(existing.columns.map((c) => c.name));
		} else {
			const maxY = store.doc.tables.reduce((m, t) => Math.max(m, t.position.y), 0);
			const fresh = store.addTable(cand, { x: 40, y: maxY + 240 });
			tableId = fresh.id;
			columnNames = new Set(fresh.columns.map((c) => c.name));
		}
		mg.factTableId = tableId;
		mg.measureColumns = mg.measureColumns.filter((c) => columnNames.has(c));
	}

	function removeFactsCalc(calcId: string) {
		factsCalcs = factsCalcs.filter((c) => c.id !== calcId);
		if (factsSelectedCalcId === calcId) factsSelectedCalcId = null;
		if (factsEditingCalcId === calcId) factsEditingCalcId = null;
	}
	function renameFactsCalc(calcId: string, name: string) {
		const c = factsCalcs.find((x) => x.id === calcId);
		if (c) c.name = name;
	}
	function findFactsCalc(calcId: string): FactsCalc | null {
		return factsCalcs.find((c) => c.id === calcId) ?? null;
	}
	// Sequence-builder ops.  We enforce the alternating shape:
	// measure-op-measure-op-… so the formula is always well-formed.
	function calcLastToken(c: FactsCalc): FactsCalcToken | null {
		return c.tokens.length === 0 ? null : c.tokens[c.tokens.length - 1];
	}
	function calcNeedsMeasure(c: FactsCalc): boolean {
		const last = calcLastToken(c);
		return last === null || last.kind === 'op';
	}
	function calcNeedsOperator(c: FactsCalc): boolean {
		const last = calcLastToken(c);
		return last !== null && last.kind === 'measure';
	}
	function addCalcMeasure(calcId: string, name: string, fromGroup = false) {
		const c = findFactsCalc(calcId);
		if (!c || !calcNeedsMeasure(c)) return;
		c.tokens.push({ kind: 'measure', name, fromGroup });
	}
	function addCalcOperator(calcId: string, op: '+' | '-' | '*' | '/') {
		const c = findFactsCalc(calcId);
		if (!c || !calcNeedsOperator(c)) return;
		c.tokens.push({ kind: 'op', op });
	}
	function popCalcToken(calcId: string) {
		const c = findFactsCalc(calcId);
		if (!c) return;
		c.tokens.pop();
	}
	function clearCalcTokens(calcId: string) {
		const c = findFactsCalc(calcId);
		if (!c) return;
		c.tokens = [];
	}
	function formatCalcOp(op: '+' | '-' | '*' | '/'): string {
		return op === '*' ? '×' : op === '/' ? '÷' : op === '-' ? '−' : '+';
	}
	// renderCalcTokens / calcMode live in ./workbench-cubes (imported above).
	function setCalcMode(calcId: string, mode: FactsCalcMode) {
		const c = findFactsCalc(calcId);
		if (!c) return;
		if (mode === 'expression' && (c.formula ?? '') === '') {
			c.formula = renderCalcTokens(c);
		}
		c.mode = mode;
	}
	function updateCalcFormula(calcId: string, formula: string) {
		const c = findFactsCalc(calcId);
		if (c) c.formula = formula;
	}
	// When a new measure group is born (via the Add button), its name
	// input gets focus + text selected so the user can rename without an
	// extra click — mirrors Hierarchies' editingHierNameId + autoFocus
	// action.
	let focusGroupId = $state<string | null>(null);
	// Mirror Hierarchies' editing pattern: single click on a measure
	// group just selects it; double click swaps to an editable input.
	// Newly-created groups auto-enter edit mode via factsEditingMeasureGroupId
	// being set in addFactsMeasureGroup.
	let factsEditingMeasureGroupId = $state<string | null>(null);
	// Group-drop helpers (`addMeasureToGroup`, `removeMeasureFromGroup`,
	// `factsDragOverGroupId`, `readFactsMeasureMoveDrag`) were removed when
	// the per-MG editor replaced the legacy drop-into-group pattern.  The
	// per-MG editor toggles `measureColumns` directly on the MG object.
	// readFactsMeasureDrag lives in ./workbench-dnd (imported above).
	$effect(() => {
		if (typeof localStorage === 'undefined') return;
		try {
			localStorage.setItem(DIM_FACTS_FRACTION_KEY, String(dimFactsFraction));
			localStorage.setItem(DIM_SECTION_COLLAPSED_KEY, String(dimSectionCollapsed));
			localStorage.setItem(FACTS_SECTION_COLLAPSED_KEY, String(factsSectionCollapsed));
			localStorage.setItem(INSPECTOR_COLLAPSED_KEY, String(inspectorCollapsed));
			localStorage.setItem(INSPECTOR_DRAWER_WIDTH_KEY, String(inspectorDrawerWidth));
			localStorage.setItem(INSPECTOR_DRAWER_HEIGHT_KEY, String(inspectorDrawerHeight));
			localStorage.setItem(INSPECTOR_TAB_KEY, inspectorTab);
			// Cubes state — the durable model now lives on the doc.  Snapshot
			// the live `factsX` variables into the selected cube, map the
			// whole cube list to the trimmed doc shape, and push it through
			// `store.setCubes` (which persists the doc).  Guarded on a JSON
			// diff so UI-only edits (search text, edit-mode toggles) don't
			// dirty the doc.  Slots stay in the legacy FACTS_STATE_KEY so
			// loadFactsSlots() (called above this $effect) keeps working.
			const docCubes = liveDocCubes();
			const nextJson = JSON.stringify(docCubes);
			if (nextJson !== lastPersistedCubesJson) {
				lastPersistedCubesJson = nextJson;
				store.setCubes(docCubes);
			}
			// Slots-only legacy payload — loadFactsSlots() reads `.slots`.
			localStorage.setItem(FACTS_STATE_KEY, JSON.stringify({ slots: [...factsSlots] }));
		} catch {
			// Storage unavailable — tolerate silently.
		}
	});
	// Vertical resize between Dimensions and Facts sections (inside the
	// main stack on the left).  Pointer-based; clamps 10–90%.
	let dimFactsResizing = $state(false);
	function handleDimFactsResizeStart(e: PointerEvent) {
		e.preventDefault();
		dimFactsResizing = true;
		const bodyEl = workbenchBodyEl;
		if (!bodyEl) return;
		const onMove = (ev: PointerEvent) => {
			const rect = bodyEl.getBoundingClientRect();
			// Dimensions section sits at top → its height = cursor.y − rect.top.
			const topHeight = ev.clientY - rect.top;
			const fraction = Math.max(0.1, Math.min(0.9, topHeight / rect.height));
			dimFactsFraction = fraction;
		};
		const onUp = () => {
			dimFactsResizing = false;
			window.removeEventListener('pointermove', onMove);
			window.removeEventListener('pointerup', onUp);
		};
		window.addEventListener('pointermove', onMove);
		window.addEventListener('pointerup', onUp);
	}
	// Resize the Inspector drawer.  Axis depends on where it's docked:
	//   - `bottom`: pointer distance from the outer's bottom edge → height.
	//   - `right`:  pointer distance from the outer's right edge  → width.
	let inspectorDrawerResizing = $state(false);
	function handleInspectorDrawerResizeStart(e: PointerEvent) {
		e.preventDefault();
		inspectorDrawerResizing = true;
		const outerEl = workbenchBodyEl?.parentElement;
		if (!outerEl) return;
		// Drag caps at 50% of the viewport — past that the user should
		// hit Maximize (fullscreen) rather than nudging via drag.  Lower
		// bounds stay wide enough to keep the drawer usable.
		const onMove = (ev: PointerEvent) => {
			const rect = outerEl.getBoundingClientRect();
			if (inspectorPosition === 'bottom') {
				const heightPx = rect.bottom - ev.clientY;
				const max = window.innerHeight / 2;
				inspectorDrawerHeight = Math.max(160, Math.min(max, heightPx));
			} else {
				const widthPx = rect.right - ev.clientX;
				const max = window.innerWidth / 2;
				inspectorDrawerWidth = Math.max(240, Math.min(max, widthPx));
			}
		};
		const onUp = () => {
			inspectorDrawerResizing = false;
			window.removeEventListener('pointermove', onMove);
			window.removeEventListener('pointerup', onUp);
		};
		window.addEventListener('pointermove', onMove);
		window.addEventListener('pointerup', onUp);
	}

	// ── Tree-view RIGHT-side inspector (horizontal split) ──────────────
	// Same Properties / Code surface as the Columns bottom inspector,
	// but docked on the right side of the tree view and resized along
	// the X axis.  Reuses inspectorTab + the inspectorProperties /
	// inspectorCode snippets — only the orientation, fraction key, and
	// collapse state are independent.
	const TREE_INSPECTOR_FRACTION_KEY = 'saiku.workbench.treeInspectorFraction';
	const TREE_INSPECTOR_COLLAPSED_KEY = 'saiku.workbench.treeInspectorCollapsed';
	function loadTreeInspectorFraction(): number {
		const DEFAULT = 0.35; // tree gets the lion's share by default
		if (typeof localStorage === 'undefined') return DEFAULT;
		const raw = localStorage.getItem(TREE_INSPECTOR_FRACTION_KEY);
		const n = raw ? Number(raw) : NaN;
		return Number.isFinite(n) && n >= 0.1 && n <= 0.9 ? n : DEFAULT;
	}
	function loadTreeInspectorCollapsed(): boolean {
		if (typeof localStorage === 'undefined') return false;
		return localStorage.getItem(TREE_INSPECTOR_COLLAPSED_KEY) === 'true';
	}
	let treeInspectorFraction = $state<number>(loadTreeInspectorFraction());
	let treeInspectorCollapsed = $state<boolean>(loadTreeInspectorCollapsed());
	let treeWorkbenchBodyEl = $state<HTMLDivElement | null>(null);
	$effect(() => {
		if (typeof localStorage === 'undefined') return;
		try {
			localStorage.setItem(TREE_INSPECTOR_FRACTION_KEY, String(treeInspectorFraction));
			localStorage.setItem(TREE_INSPECTOR_COLLAPSED_KEY, String(treeInspectorCollapsed));
		} catch {
			// Storage unavailable — tolerate silently.
		}
	});
	let treeInspectorResizing = $state(false);
	function handleTreeInspectorResizeStart(e: PointerEvent) {
		e.preventDefault();
		treeInspectorResizing = true;
		const bodyEl = treeWorkbenchBodyEl;
		if (!bodyEl) return;
		const onMove = (ev: PointerEvent) => {
			const rect = bodyEl.getBoundingClientRect();
			// Distance from cursor to right edge = inspector's intended width.
			const rightWidth = rect.right - ev.clientX;
			const fraction = Math.max(0.1, Math.min(0.9, rightWidth / rect.width));
			treeInspectorFraction = fraction;
		};
		const onUp = () => {
			treeInspectorResizing = false;
			window.removeEventListener('pointermove', onMove);
			window.removeEventListener('pointerup', onUp);
		};
		window.addEventListener('pointermove', onMove);
		window.addEventListener('pointerup', onUp);
	}

	// ── Delete-confirmation modal ────────────────────────────────────
	// Dim/hier deletes route through here so the user sees what they
	// lose (attribute count, hierarchy count, member count, etc.)
	// before a destructive action.
	interface ConfirmDelete {
		kind: 'dim' | 'hier';
		dimId: string;
		hierId?: string;
		title: string;
		impact: string[];
	}
	let confirmDelete = $state<ConfirmDelete | null>(null);
	// requestDeleteDimension was called by the row-level "X" button that
	// used to sit on every dim in the pane; the button is gone (deletes
	// route through the Inspector now), so the helper is unused.  Kept
	// the confirm-modal machinery for the hier/measure delete paths.
	function requestDeleteHierarchy(dimId: string, hierId: string) {
		const d = store.dimensions.find((x) => x.id === dimId);
		const h = d?.hierarchies.find((x) => x.id === hierId);
		if (!d || !h) return;
		confirmDelete = {
			kind: 'hier',
			dimId,
			hierId,
			title: `Delete hierarchy "${h.name}"?`,
			impact: [
				`${h.levels.length} member level${h.levels.length === 1 ? '' : 's'} will be removed from this hierarchy.`,
				`The attributes themselves stay in the dimension's set A and can be re-added to another hierarchy.`
			]
		};
	}
	function confirmDeletion() {
		if (!confirmDelete) return;
		if (confirmDelete.kind === 'dim') {
			store.removeDimension(confirmDelete.dimId);
			if (selectedDimensionId === confirmDelete.dimId) {
				selectedDimensionId = null;
				selectedHierarchyId = null;
			}
		} else if (confirmDelete.kind === 'hier' && confirmDelete.hierId) {
			if (selectedHierarchyId === confirmDelete.hierId) selectedHierarchyId = null;
			store.removeHierarchy(confirmDelete.dimId, confirmDelete.hierId);
		}
		confirmDelete = null;
	}
	function cancelDeletion() {
		confirmDelete = null;
	}

	// Attribute-removal confirmation: if a hierarchy depends on the
	// attribute (any level matches by tableId + columnName), warn the
	// user before stripping it.  Confirming removes the attribute AND
	// all matching levels from each affected hierarchy.
	interface ConfirmRemoveAttribute {
		dimId: string;
		tableId: string;
		columnName: string;
		affected: { hierId: string; hierName: string }[];
	}
	let confirmRemoveAttr = $state<ConfirmRemoveAttribute | null>(null);
	// Kept for the confirmation modal + potential future direct-remove
	// paths — currently only reachable if a caller re-wires it (the pane
	// prop was dropped when the attributes-edit draft flow landed).
	// eslint-disable-next-line @typescript-eslint/no-unused-vars
	function requestRemoveAttribute(dimId: string, tableId: string, columnName: string) {
		const d = store.dimensions.find((x) => x.id === dimId);
		if (!d) return;
		const affected: { hierId: string; hierName: string }[] = [];
		for (const h of d.hierarchies) {
			if (h.levels.some((l) => l.tableId === tableId && l.columnName === columnName)) {
				affected.push({ hierId: h.id, hierName: h.name });
			}
		}
		if (affected.length === 0) {
			store.removeAttribute(dimId, tableId, columnName);
			return;
		}
		confirmRemoveAttr = { dimId, tableId, columnName, affected };
	}
	function confirmRemoveAttribute() {
		if (!confirmRemoveAttr) return;
		const { dimId, tableId, columnName, affected } = confirmRemoveAttr;
		for (const a of affected) {
			const d = store.dimensions.find((x) => x.id === dimId);
			const h = d?.hierarchies.find((x) => x.id === a.hierId);
			const lvl = h?.levels.find((l) => l.tableId === tableId && l.columnName === columnName);
			if (lvl) store.removeLevel(dimId, a.hierId, lvl.id);
		}
		store.removeAttribute(dimId, tableId, columnName);
		confirmRemoveAttr = null;
	}
	function cancelRemoveAttribute() {
		confirmRemoveAttr = null;
	}

	// Double-click-to-edit on title labels.  Default: titles render as
	// spans, so clicking the row body fires selection.  Double-clicking
	// the title switches the span to an inline input.  On blur or
	// Enter, switches back.  Newly-created dims/hiers automatically
	// enter edit mode so the user can name them immediately.
	let editingDimNameId = $state<string | null>(null);
	let editingHierNameId = $state<string | null>(null);
	// Inline caption editor for a measure inside a Measure Group.
	// Value shape: `${groupId}::${columnName}` (or null).  Set on
	// pencil-click / double-click; cleared on blur, Enter, Escape.
	let editingMeasureCaption = $state<string | null>(null);

	function commitMeasureCaption(g: WorkbenchMeasureGroup, col: string, nextValue: string): void {
		const clean = nextValue.trim();
		const current = g.measureCaptions ?? {};
		if (!clean || clean === col) {
			// Empty or equal-to-column-name → treat as "no override";
			// keep the map lean so serialisation doesn't carry noise.
			if (col in current) {
				const next = { ...current };
				delete next[col];
				g.measureCaptions = next;
			}
		} else {
			g.measureCaptions = { ...current, [col]: clean };
		}
		editingMeasureCaption = null;
	}

	// Same edit/save shell on Attributes + Hierarchies per image 21.
	//   - Attributes edit:  full checkbox list of T's columns (defines A).
	//   - Attributes saved: condensed view of just the saved A; each row
	//                       is draggable so the user can pull attributes
	//                       into Col 4 (Content).  The edit/save toggle
	//                       IS the "hide unused" — saved mode hides what
	//                       isn't in A, edit mode shows the full T.
	//   - Hierarchies edit: rename inputs + Make hierarchy.
	//   - Hierarchies saved: hierarchies as read-only labels.
	let attributesPaneMode = $state<'edit' | 'saved'>('edit');
	// Sort state for the Dimensions table.  Defaults to "creation
	// order" — represented internally as sortKey='idx' / asc which
	// the sorter treats as a no-op.
	type DimSortKey = 'idx' | 'name' | 'type' | 'cols';
	let dimSortKey = $state<DimSortKey>('idx');
	let dimSortDir = $state<'asc' | 'desc'>('asc');
	// Which dim's Saved-pane row is currently in "confirm delete" state.
	// Only one row can be confirming at a time; clicking ✕ on a
	// different row moves the confirm to that one.
	let deletingDimId = $state<string | null>(null);
	function toggleDimSort(key: DimSortKey) {
		if (dimSortKey === key) {
			// Same column → flip direction, or unset back to creation order
			// on the third click.
			if (dimSortDir === 'asc') {
				dimSortDir = 'desc';
			} else {
				dimSortKey = 'idx';
				dimSortDir = 'asc';
			}
		} else {
			dimSortKey = key;
			dimSortDir = 'asc';
		}
	}
	// Drag-into-Hierarchies-Name-column → auto-create a single-level
	// hierarchy named after the dropped attribute.  Mirror of the facts
	// side's drag-into-Measure-Groups-Name → single-measure group.
	let dimDragOverHierName = $state<boolean>(false);
	function addHierarchyFromAttribute(dimId: string, tableId: string, columnName: string) {
		const h = store.addHierarchy(dimId, columnName);
		if (h) {
			store.addLevel(dimId, h.id, { tableId, columnName });
			selectedHierarchyId = h.id;
			hierarchyExplicitlyClicked = true;
			// Open the name in rename mode so the autoFocus action
			// focuses + selects the text immediately on drop.
			editingHierNameId = h.id;
		}
	}
	// When the user picks a different dimension, default the Attributes
	// pane to whichever mode is useful: 'saved' if the dim already has
	// attributes (so the Hierarchies pane is instantly usable) or
	// 'edit' if it's empty (so the user starts building immediately).
	// Manual toggle still wins until the next dim switch.
	let lastAttrInitDimId = $state<string | null>(null);
	$effect(() => {
		const dim = selectedDimension;
		if (!dim) {
			lastAttrInitDimId = null;
			return;
		}
		if (dim.id !== lastAttrInitDimId) {
			lastAttrInitDimId = dim.id;
			attributesPaneMode = (dim.attributes?.length ?? 0) > 0 ? 'saved' : 'edit';
		}
	});

	// Attribute / level drag MIMEs + payload types + readAttributeDrag /
	// readLevelMoveDrag / isContentDrag live in ./workbench-dnd (imported
	// above). The attribute serialiser stays here (component-facing).
	function handleAttributeDragStart(e: DragEvent, payload: AttributeDragPayload): void {
		if (!e.dataTransfer) return;
		e.dataTransfer.setData(ATTRIBUTE_MIME, JSON.stringify(payload));
		e.dataTransfer.effectAllowed = 'copyMove';
	}
	// Highlight target hierarchy row in Col 4 during a content drag.
	let contentDragOverHierarchyId = $state<string | null>(null);
	// Hierarchy level chips render alphabetically — display-only, doesn't
	// mutate the stored level order.  Toggle removed: hierarchies are
	// unordered sets in Mondrian's model, so ordered display would imply
	// semantics the schema doesn't carry.
	const selectedDimension = $derived(
		selectedDimensionId
			? (store.dimensions.find((d) => d.id === selectedDimensionId) ?? null)
			: null
	);
	// Only honour an explicit hierarchy selection — no fallback to the
	// first hierarchy.  The Hierarchy content pane wants null when the
	// user hasn't picked one, so it can render the "Pick a hierarchy"
	// empty state instead of bleeding the first one's chips in.
	const selectedHierarchy = $derived(
		selectedDimension && selectedHierarchyId
			? (selectedDimension.hierarchies.find((h) => h.id === selectedHierarchyId) ?? null)
			: null
	);
	const selectedMeasure = $derived(
		selectedMeasureId ? (store.measures.find((m) => m.id === selectedMeasureId) ?? null) : null
	);
	const selectedAttribute = $derived.by(() => {
		if (!selectedAttributeKey || !selectedDimension) return null;
		const [tableId, columnName] = selectedAttributeKey.split('::');
		return (
			(selectedDimension.attributes ?? []).find(
				(a) => a.tableId === tableId && a.columnName === columnName
			) ?? null
		);
	});
	// The level (a member of the selected hierarchy) whose chip the user clicked
	// in the Hierarchies pane. Drives the <Level> inspector branch (rename /
	// caption / levelType). Level ids are unique so a plain id lookup suffices.
	const selectedLevel = $derived(
		selectedHierarchy && selectedLevelId
			? (selectedHierarchy.levels.find((l) => l.id === selectedLevelId) ?? null)
			: null
	);

	// ── Cube readiness — derived "what's missing" for sample-data / try-query ──
	// The new Inspector tabs need to know whether the cube has enough on the
	// canvas to actually run.  `missing` is a list of plain-English deficiencies
	// the empty-state can render so the user knows what to author next.
	//
	// Source of truth for "the fact table" is `factsSelectedTableId` (set by
	// the workbench's fact-table picker), not `role === 'fact'` — the picker
	// is the canonical workbench-side selection.  Fall back to role only when
	// no workbench pick has been made yet (legacy / freshly loaded canvases).
	const cubeFactTable = $derived.by(() => {
		const picked = factsSelectedTableId
			? store.doc.tables.find((t) => t.id === factsSelectedTableId)
			: undefined;
		return picked ?? store.doc.tables.find((t) => t.role === 'fact') ?? null;
	});
	const sampleDataReadiness = $derived.by<{ ready: boolean; missing: string[] }>(() => {
		const missing: string[] = [];
		if (!cubeFactTable) missing.push('Pick a fact table');
		if (!store.doc.connectionId) missing.push('Pick a data source');
		return { ready: missing.length === 0, missing };
	});
	const tryQueryReadiness = $derived.by<{ ready: boolean; missing: string[] }>(() => {
		const missing: string[] = [];
		if (!cubeFactTable) missing.push('Pick a fact table');
		if ((store.measures ?? []).length === 0) missing.push('Add at least one measure');
		const hasDim =
			(store.dimensions ?? []).length > 0 ||
			store.doc.joins.some(
				(j) => j.sourceTableId === cubeFactTable?.id || j.targetTableId === cubeFactTable?.id
			);
		if (!hasDim) missing.push('Add at least one dimension');
		return { ready: missing.length === 0, missing };
	});

	// Sample-data fetch state — keyed by `${connectionId}::${factTableName}`
	// so switching connection or fact retriggers a load.
	type SampleDataState =
		| { kind: 'idle' }
		| { kind: 'loading' }
		| { kind: 'error'; message: string }
		| { kind: 'ready'; columns: string[]; rows: Array<Record<string, unknown>> };
	let sampleDataKey = $state<string | null>(null);
	let sampleData = $state<SampleDataState>({ kind: 'idle' });
	// Row-count picker for the Sample data tab.  Bounded by the proxy
	// (LIMIT_MAX = 100).  Preset chips render below; changing the chip
	// rekeys sampleDataKey and the effect refires.
	const SAMPLE_ROW_LIMITS: readonly number[] = [5, 25, 50, 100];
	let sampleRowLimit = $state<number>(5);
	async function loadSampleData(): Promise<void> {
		if (!cubeFactTable || !store.doc.connectionId) return;
		const limit = sampleRowLimit;
		const key = `${store.doc.connectionId}::${cubeFactTable.schema ?? ''}::${cubeFactTable.name}::${limit}`;
		if (sampleDataKey === key && (sampleData.kind === 'loading' || sampleData.kind === 'ready')) {
			return;
		}
		sampleDataKey = key;
		sampleData = { kind: 'loading' };
		try {
			const tableArg = cubeFactTable.schema
				? `${cubeFactTable.schema}.${cubeFactTable.name}`
				: cubeFactTable.name;
			const resp = await designerBackend.sample(store.doc.connectionId, tableArg, limit);
			if (!resp.ok) {
				sampleData = {
					kind: 'error',
					message: `Couldn't fetch sample rows (HTTP ${resp.status})`
				};
				return;
			}
			const body = (await resp.json()) as {
				columns?: string[];
				rows?: Array<Record<string, unknown> | unknown[]>;
			};
			const cols = body.columns ?? [];
			const rawRows = body.rows ?? [];
			// Gateway can return rows as ARRAYS (positional values) or as
			// OBJECTS keyed by column name — accept both.  Positional arrays
			// are common when the gateway proxies a generic JDBC ResultSet.
			// Zip array rows back into objects so the renderer's `row[col]`
			// lookup just works.
			const rows = rawRows.map((r) => {
				if (Array.isArray(r)) {
					const obj: Record<string, unknown> = {};
					cols.forEach((c, i) => {
						obj[c] = r[i];
					});
					return obj;
				}
				return r;
			});
			sampleData = { kind: 'ready', columns: cols, rows };
		} catch (err) {
			sampleData = {
				kind: 'error',
				message: err instanceof Error ? err.message : 'Failed to load sample rows.'
			};
		}
	}
	// Re-fetch when the Sample data tab becomes active and the cube is
	// ready.  Sample data now lives on the Confirm cube tab
	// (validateTab === 'sample-data') as well as the old inspector drawer
	// tab (inspectorTab === 'sample-data') — trigger on either.
	$effect(() => {
		const onValidate = store.mode === 'validate' && validateTab === 'sample-data';
		const onInspector = inspectorTab === 'sample-data';
		if (!onValidate && !onInspector) return;
		if (!sampleDataReadiness.ready) return;
		// Read sampleRowLimit here so the effect re-runs when the chip
		// changes — $effect tracks synchronous reads in this body, not
		// reads inside the async loadSampleData microtask.
		void sampleRowLimit;
		void loadSampleData();
	});

	// ── Try a Query — runs MDX against the unsaved cube via the existing
	// `/api/inference/try-query` endpoint (originally built for the AI
	// inference flow; reused here since the proposal shape + payload are
	// the same).  Builds a starter MDX from the cube's first measure ×
	// first dim level and posts.  Result rendered as a flat table inline.
	type TryQueryState =
		| { kind: 'idle' }
		| { kind: 'loading'; mdx: string }
		| { kind: 'error'; mdx: string; message: string }
		| {
				kind: 'ready';
				mdx: string;
				columns: string[];
				rows: Array<Record<string, unknown>>;
				truncated?: boolean;
				durationMs?: number;
		  };
	let tryQueryState = $state<TryQueryState>({ kind: 'idle' });
	// Try-a-query composer — user picks measure / dim / hierarchy / level
	// via dropdowns; the picks generate an MDX seed.  "Show MDX" toggles
	// a textarea below; when set, the textarea contents take precedence
	// over the generated MDX so power users can hand-edit.
	let tryQueryMeasure = $state<string | null>(null);
	let tryQueryDimensionId = $state<string | null>(null);
	let tryQueryHierarchyId = $state<string | null>(null);
	let tryQueryLevelId = $state<string | null>(null);
	let tryQueryMdxOverride = $state<string | null>(null);
	// Resolve picked ids → objects, defaulting to first-available if the
	// user hasn't touched the dropdowns yet.
	const tryQueryPickedMeasure = $derived.by(() =>
		tryQueryMeasure && (store.measures ?? []).some((m) => m.name === tryQueryMeasure)
			? tryQueryMeasure
			: ((store.measures ?? [])[0]?.name ?? null)
	);
	const tryQueryPickedDim = $derived.by(() =>
		tryQueryDimensionId
			? ((store.dimensions ?? []).find((d) => d.id === tryQueryDimensionId) ??
				(store.dimensions ?? [])[0] ??
				null)
			: ((store.dimensions ?? [])[0] ?? null)
	);
	const tryQueryPickedHier = $derived.by(() => {
		const dim = tryQueryPickedDim;
		if (!dim) return null;
		const hiers = dim.hierarchies ?? [];
		return (
			(tryQueryHierarchyId ? hiers.find((h) => h.id === tryQueryHierarchyId) : null) ??
			hiers[0] ??
			null
		);
	});
	const tryQueryPickedLevel = $derived.by(() => {
		const hier = tryQueryPickedHier;
		if (!hier) return null;
		const levels = hier.levels ?? [];
		return (
			(tryQueryLevelId ? levels.find((l) => l.id === tryQueryLevelId) : null) ?? levels[0] ?? null
		);
	});
	// MDX generated from the picked measure / dim / hier / level.  Used
	// as the Run source when the MDX override is null.
	const tryQueryGeneratedMdx = $derived.by<string | null>(() => {
		const cubeName = selectedCube?.name;
		const m = tryQueryPickedMeasure;
		const dim = tryQueryPickedDim;
		const hier = tryQueryPickedHier;
		const level = tryQueryPickedLevel;
		if (!cubeName || !m || !dim || !hier || !level) return null;
		return [
			`SELECT NON EMPTY { [Measures].[${m}] } ON COLUMNS,`,
			`       NON EMPTY { [${dim.name}].[${hier.name}].[${level.name}].MEMBERS } ON ROWS`,
			`FROM [${cubeName}]`
		].join('\n');
	});
	// Source of truth for Run: user override (if set), else the picker.
	const tryQueryEffectiveMdx = $derived(tryQueryMdxOverride ?? tryQueryGeneratedMdx);
	function buildTryQueryProposal(): {
		schemaName: string;
		cubes: Array<{
			name: string;
			factTableSchema?: string | null;
			factTableName: string;
			measures: Array<{ name: string; aggregator: string; column?: string }>;
			dimensions: Array<{
				name: string;
				foreignKey: string;
				tableSchema?: string | null;
				tableName: string;
				primaryKey: string;
				levels: Array<{ name: string; column: string; type: string; levelType?: string | null }>;
			}>;
		}>;
	} | null {
		const cube = selectedCube;
		if (!cube) return null;
		// The gateway parses this into its full SchemaProposal IR: a
		// MeasureProposal needs name + aggregator (+ column unless count(*)); a
		// DimensionProposal needs foreignKey + tableName + primaryKey + levels
		// (with a column). Build the complete shape from the canvas doc.
		const fact = store.doc.tables.find((t) => t.role === 'fact') ?? null;
		if (!fact) return null;

		const measures = (store.measures ?? [])
			.filter((m) => m.tableId === fact.id)
			.map((m) => {
				const mp: { name: string; aggregator: string; column?: string } = {
					name: m.name,
					aggregator: m.aggregator
				};
				if (m.columnName) mp.column = m.columnName;
				return mp;
			});
		if (measures.length === 0) return null;

		const factCol = (name: string | null | undefined): string | null =>
			name
				? (fact.columns.find((c) => c.name.toLowerCase() === name.toLowerCase())?.name ?? null)
				: null;
		const resolveFk = (d: SchemaCanvasDimension): string | null => {
			if (d.foreignKey) return d.foreignKey;
			const dimTableId = d.primaryKeyTableId ?? d.sourceTableId ?? null;
			if (dimTableId) {
				for (const j of store.doc.joins) {
					if (j.sourceTableId === fact.id && j.targetTableId === dimTableId)
						return j.sourceColumnName;
					if (j.targetTableId === fact.id && j.sourceTableId === dimTableId)
						return j.targetColumnName;
				}
			}
			const dimTable = store.doc.tables.find((t) => t.id === dimTableId);
			return factCol(d.primaryKey) ?? (dimTable ? factCol(`${dimTable.name}_id`) : null);
		};

		const dimensions = (store.dimensions ?? [])
			.map((d) => {
				const dimTable = store.doc.tables.find(
					(t) => t.id === (d.primaryKeyTableId ?? d.sourceTableId)
				);
				return {
					name: d.name,
					foreignKey: resolveFk(d) ?? '',
					tableSchema: dimTable?.schema ?? null,
					tableName: dimTable?.name ?? '',
					primaryKey: d.primaryKey ?? '',
					levels: d.hierarchies.flatMap((h) =>
						h.levels.map((l) => ({ name: l.name, column: l.columnName, type: 'String' }))
					)
				};
			})
			// Only dimensions the gateway IR can accept (all required fields + ≥1 level).
			.filter((d) => d.tableName && d.foreignKey && d.primaryKey && d.levels.length > 0);

		return {
			schemaName: store.doc.label || 'Untitled',
			cubes: [
				{
					name: cube.name,
					factTableSchema: fact.schema ?? null,
					factTableName: fact.name,
					measures,
					dimensions
				}
			]
		};
	}
	function buildTryQueryMdx(): string | null {
		const cubeName = selectedCube?.name;
		const firstMeasure = store.measures?.[0]?.name;
		const firstDim = store.dimensions?.[0];
		const firstHier = firstDim?.hierarchies?.[0];
		const firstLevel = firstHier?.levels?.[0];
		if (!cubeName || !firstMeasure || !firstDim || !firstHier || !firstLevel) return null;
		return [
			`SELECT NON EMPTY { [Measures].[${firstMeasure}] } ON COLUMNS,`,
			`       NON EMPTY { [${firstDim.name}].[${firstHier.name}].[${firstLevel.name}].MEMBERS } ON ROWS`,
			`FROM [${cubeName}]`
		].join('\n');
	}
	async function runTryQuery(): Promise<void> {
		// Prefer user override (edited MDX textarea) → generated MDX
		// (from the dropdowns) → legacy first-of-everything fallback.
		const mdx =
			(tryQueryMdxOverride && tryQueryMdxOverride.trim().length > 0
				? tryQueryMdxOverride
				: tryQueryGeneratedMdx) ?? buildTryQueryMdx();
		const proposal = buildTryQueryProposal();
		const cube = selectedCube;
		const connectionId = store.doc.connectionId;
		if (!mdx || !proposal || !cube || !connectionId) return;
		tryQueryState = { kind: 'loading', mdx };
		try {
			const resp = await designerBackend.tryQuery({
				proposal,
				connectionId,
				mdx,
				cubeName: cube.name
			});
			const body = (await resp.json().catch(() => null)) as {
				columns?: string[];
				rows?: Array<Record<string, unknown> | unknown[]>;
				truncated?: boolean;
				durationMs?: number;
				error?: string;
				message?: string;
			} | null;
			if (!resp.ok || !body) {
				tryQueryState = {
					kind: 'error',
					mdx,
					message: body?.message ?? `Couldn't run the preview query (HTTP ${resp.status})`
				};
				return;
			}
			const cols = body.columns ?? [];
			const rawRows = body.rows ?? [];
			// Same array-or-object row normalisation as Sample Data.
			const rows = rawRows.map((r) => {
				if (Array.isArray(r)) {
					const obj: Record<string, unknown> = {};
					cols.forEach((c, i) => {
						obj[c] = r[i];
					});
					return obj;
				}
				return r;
			});
			tryQueryState = {
				kind: 'ready',
				mdx,
				columns: cols,
				rows,
				truncated: body.truncated,
				durationMs: body.durationMs
			};
		} catch (err) {
			tryQueryState = {
				kind: 'error',
				mdx,
				message: err instanceof Error ? err.message : "Couldn't run the preview query"
			};
		}
	}

	// When set, the dim card whose id matches this auto-focuses its name
	// input on mount and selects whatever's already there.  The user
	// expects to start typing the name immediately after clicking "Add
	// dimension"; without this the focus would stay on the trigger button.
	let focusDimId = $state<string | null>(null);
	function addDimensionAndFocus() {
		const dim = store.addDimension();
		focusDimId = dim.id;
		// New dim → drop straight into rename mode so the user can
		// type its name without needing to double-click.
		editingDimNameId = dim.id;
	}
	/** Use:focus action — wires a fresh-rendered input to grab focus +
	 *  select-all when the dim it belongs to matches `focusDimId`. The
	 *  flag is cleared after first use so a re-render doesn't keep
	 *  stealing focus. */
	function autoFocus(node: HTMLInputElement, shouldFocus: boolean) {
		function tryFocus(active: boolean) {
			if (active) {
				node.focus();
				node.select();
				focusDimId = null;
			}
		}
		tryFocus(shouldFocus);
		return {
			update(active: boolean) {
				tryFocus(active);
			}
		};
	}

	// Tree-view per-row collapse state, independent of the cards view.
	let collapsedTreeDimensions = $state<Set<string>>(new Set());
	let collapsedTreeHierarchies = $state<Set<string>>(new Set());
	// Cube-aware tree state.  Cubes + measure groups are collapsible
	// (default expanded so a fresh user sees their structure) and
	// selectable — when the user clicks a cube or MG row, the inspector
	// Properties tab routes to a cube-props view or the per-MG editor
	// (paneMeasureGroupContent) respectively.
	let collapsedTreeCubes = $state<Set<string>>(new Set());
	let collapsedTreeMeasureGroups = $state<Set<string>>(new Set());
	let collapsedSharedDimensions = $state<boolean>(false);
	let selectedTreeCubeId = $state<string | null>(null);
	let selectedTreeMeasureGroupId = $state<string | null>(null);
	// Which scope the tree column is focused on.  The schema has TWO
	// concerns — shared dimensions (their hierarchies + attributes) and
	// cubes (their MGs + per-MG facts/measures/dim links).  Surfacing
	// both at once made the column header read confusingly as
	// "DIMENSIONS" while listing Cubes underneath; the toggle lets the
	// user pick one focus.  Default: 'cubes' since the workbench is
	// primarily for cube authoring.
	let treeFocus = $state<'dims' | 'cubes'>('cubes');

	function toggleTreeDimension(id: string) {
		const next = new Set(collapsedTreeDimensions);
		if (next.has(id)) next.delete(id);
		else next.add(id);
		collapsedTreeDimensions = next;
	}

	function toggleTreeHierarchy(id: string) {
		const next = new Set(collapsedTreeHierarchies);
		if (next.has(id)) next.delete(id);
		else next.add(id);
		collapsedTreeHierarchies = next;
	}

	function toggleTreeMeasureGroup(id: string) {
		const next = new Set(collapsedTreeMeasureGroups);
		if (next.has(id)) next.delete(id);
		else next.add(id);
		collapsedTreeMeasureGroups = next;
	}

	// Bulk-collapse / bulk-expand for the tree view header.  Touches
	// every collapse set + the Shared Dimensions toggle in one swoop
	// so the user can see-all-at-once or fold-everything-down without
	// chevron-hunting node-by-node.  For MG ids the live `factsX` set
	// holds the selected cube's MGs; non-selected cubes carry their
	// own snapshot — collect both so a "collapse all" actually does.
	function expandAllTree() {
		collapsedTreeDimensions = new Set();
		collapsedTreeHierarchies = new Set();
		collapsedTreeCubes = new Set();
		collapsedTreeMeasureGroups = new Set();
		collapsedSharedDimensions = false;
	}
	// Derived "anything collapsed?" for the stacked-chevron toggle.
	const anyTreeCollapsed = $derived(
		collapsedTreeDimensions.size > 0 ||
			collapsedTreeHierarchies.size > 0 ||
			collapsedTreeCubes.size > 0 ||
			collapsedTreeMeasureGroups.size > 0 ||
			collapsedSharedDimensions
	);
	function collapseAllTree() {
		collapsedTreeDimensions = new Set(store.dimensions.map((d) => d.id));
		collapsedTreeHierarchies = new Set(
			store.dimensions.flatMap((d) => (d.hierarchies ?? []).map((h) => h.id))
		);
		collapsedTreeCubes = new Set(cubes.map((c) => c.id));
		const allMGIds: string[] = [];
		for (const c of cubes) {
			const mgs = c.id === selectedCubeId ? factsMeasureGroups : c.measureGroups;
			for (const mg of mgs) allMGIds.push(mg.id);
		}
		collapsedTreeMeasureGroups = new Set(allMGIds);
		collapsedSharedDimensions = true;
	}

	// Selecting a measure group node.  Also switches cubes if the MG
	// belongs to a different cube — the per-MG editor reads from the
	// live factsMeasureGroups, so the right cube has to be hydrated
	// before the editor can mutate it.
	function selectTreeMeasureGroup(cubeId: string, mgId: string) {
		if (cubeId !== selectedCubeId) switchCube(cubeId);
		selectedTreeCubeId = cubeId;
		selectedTreeMeasureGroupId = mgId;
		factsSelectedGroupId = mgId;
		selectedDimensionId = null;
		selectedHierarchyId = null;
		selectedMeasureId = null;
		selectedAttributeKey = null;
		selectedLevelId = null;
		focus('mg', mgId);
		inspectorTab = 'properties';
	}
	// Inspector <Cube> "Add measure group" — hydrate the target cube (so the
	// live factsMeasureGroups is the right one), append a group, then point
	// the tree selection at it.  Extracted so InspectorProperties.svelte can
	// fire it via one callback rather than binding selectedTreeMeasureGroupId
	// back out (which it only writes, never reads).
	function addMeasureGroupToCube(cubeId: string): void {
		if (cubeId !== selectedCubeId) switchCube(cubeId);
		addFactsMeasureGroup();
		selectedTreeMeasureGroupId = factsSelectedGroupId;
	}

	// ── Selection transitions fired from FactsMeasuresPane ───────────────
	// The Facts pane is write-only for these selection ids (it never reads
	// them back), so they route through callbacks instead of bound props —
	// the shell stays the single source of truth for the cross-pane selection.
	function selectMgMeasure(measureId: string): void {
		selectedMeasureId = measureId;
		selectedAttributeKey = null;
		selectedLevelId = null;
		selectedHierarchyId = null;
		focus('measure', measureId);
	}
	function selectMgDimension(dimId: string): void {
		selectedDimensionId = dimId;
		selectedMeasureId = null;
		selectedAttributeKey = null;
		selectedLevelId = null;
		selectedHierarchyId = null;
	}
	function selectFactsCalc(calcId: string): void {
		factsSelectedCalcId = calcId;
		focus('calc', calcId);
	}

	// Derived getters used by the inspector + MG tree row.  Read from
	// the LIVE factsMeasureGroups (not cube.measureGroups) because the
	// per-MG editor mutates the live state — only the selected cube's
	// MGs are live; switching cubes snapshots/hydrates.
	const selectedTreeMeasureGroup = $derived(
		selectedTreeMeasureGroupId
			? (factsMeasureGroups.find((g) => g.id === selectedTreeMeasureGroupId) ?? null)
			: null
	);
	const selectedTreeCube = $derived(
		selectedTreeCubeId ? (cubes.find((c) => c.id === selectedTreeCubeId) ?? null) : null
	);

	// ── Per-tab inspector memory (D&H ↔ F&M) ────────────────────────
	// The bottom Inspector panel is shared by the Dimensions &
	// Hierarchies tab and the Facts & Measures tab, but each tab has
	// its own idea of "what to inspect".  Without per-tab memory, the
	// last selection on one tab bleeds through to the other (e.g.
	// selecting a dim on D&H, switching to F&M, still seeing the dim).
	//
	// Contract:
	//   • First visit to a primary tab (workbench / facts) → snap to
	//     the first thing on that tab (first dim on D&H; first measure
	//     → first MG → cube on F&M).
	//   • Subsequent visits to a primary tab → restore whatever was
	//     last selected on THAT tab.
	//   • Entering canvas / validate mode is a no-op — those views
	//     don't render the shared Inspector.
	//
	// Implementation: capture every id feeding InspectorProperties'
	// else-if chain (treeMG > treeCube > measure > level > attr >
	// hier > dim) into an outgoing snapshot on tab exit, then clear
	// and restore/default on tab enter.  Restoration validates each
	// id still exists (a dim / measure could have been deleted while
	// the tab was inactive); anything dangling drops back to the
	// first-visit default.
	type InspectorSnapshot = {
		treeMGId: string | null;
		treeCubeId: string | null;
		measureId: string | null;
		dimensionId: string | null;
		hierarchyId: string | null;
		attributeKey: string | null;
		levelId: string | null;
		hierExplicit: boolean;
		focus: { kind: FocusKind; id: string } | null;
	};
	let lastDhSnapshot: InspectorSnapshot | null = null;
	let lastFmSnapshot: InspectorSnapshot | null = null;

	function captureInspectorSnapshot(): InspectorSnapshot {
		return {
			treeMGId: selectedTreeMeasureGroupId,
			treeCubeId: selectedTreeCubeId,
			measureId: selectedMeasureId,
			dimensionId: selectedDimensionId,
			hierarchyId: selectedHierarchyId,
			attributeKey: selectedAttributeKey,
			levelId: selectedLevelId,
			hierExplicit: hierarchyExplicitlyClicked,
			focus: focusSubject
		};
	}

	function clearInspectorSelection(): void {
		selectedTreeMeasureGroupId = null;
		selectedTreeCubeId = null;
		selectedMeasureId = null;
		selectedDimensionId = null;
		selectedHierarchyId = null;
		selectedAttributeKey = null;
		selectedLevelId = null;
		hierarchyExplicitlyClicked = false;
		focusSubject = null;
	}

	/** Push a snapshot back into the live ids, dropping any id whose
	 *  referent no longer exists.  Returns true when at least one id
	 *  survived — callers use that to decide whether to fall back to a
	 *  fresh first-visit default. */
	function restoreInspectorSnapshot(snap: InspectorSnapshot): boolean {
		const dimAlive = snap.dimensionId
			? store.dimensions.some((d) => d.id === snap.dimensionId)
			: false;
		const measureAlive = snap.measureId
			? store.measures.some((m) => m.id === snap.measureId)
			: false;
		const mgAlive = snap.treeMGId
			? factsMeasureGroups.some((g) => g.id === snap.treeMGId) ||
				cubes.some((c) => (c.measureGroups ?? []).some((g) => g.id === snap.treeMGId))
			: false;
		const cubeAlive = snap.treeCubeId ? cubes.some((c) => c.id === snap.treeCubeId) : false;
		selectedTreeMeasureGroupId = mgAlive ? snap.treeMGId : null;
		selectedTreeCubeId = cubeAlive ? snap.treeCubeId : null;
		selectedMeasureId = measureAlive ? snap.measureId : null;
		selectedDimensionId = dimAlive ? snap.dimensionId : null;
		selectedHierarchyId = dimAlive ? snap.hierarchyId : null;
		selectedAttributeKey = dimAlive ? snap.attributeKey : null;
		selectedLevelId = dimAlive ? snap.levelId : null;
		hierarchyExplicitlyClicked = snap.hierExplicit;
		// Only restore focus if its referent survives; otherwise leave
		// null so the row that ends up as the default paints as focused.
		const focusAlive =
			snap.focus === null ||
			(snap.focus.kind === 'dim' && dimAlive) ||
			(snap.focus.kind === 'measure' && measureAlive) ||
			(snap.focus.kind === 'mg' && mgAlive) ||
			(snap.focus.kind === 'cube' && cubeAlive) ||
			(snap.focus.kind === 'hierarchy' && dimAlive) ||
			(snap.focus.kind === 'attribute' && dimAlive) ||
			(snap.focus.kind === 'calc' && true);
		focusSubject = focusAlive ? snap.focus : null;
		return dimAlive || measureAlive || mgAlive || cubeAlive;
	}

	function applyDefaultDhSelection(): void {
		const first = store.dimensions[0];
		if (first) {
			selectedDimensionId = first.id;
			focus('dim', first.id);
		}
	}

	function applyDefaultFmSelection(): void {
		const firstMeasure = store.measures[0];
		if (firstMeasure) {
			selectedMeasureId = firstMeasure.id;
			focus('measure', firstMeasure.id);
			return;
		}
		const firstMg = factsMeasureGroups[0];
		if (firstMg) {
			selectedTreeMeasureGroupId = firstMg.id;
			selectedTreeCubeId = selectedCubeId;
			factsSelectedGroupId = firstMg.id;
			focus('mg', firstMg.id);
			return;
		}
		if (selectedCubeId) {
			selectedTreeCubeId = selectedCubeId;
			focus('cube', selectedCubeId);
		}
	}

	function enterDhTab(): void {
		clearInspectorSelection();
		if (lastDhSnapshot) {
			restoreInspectorSnapshot(lastDhSnapshot);
			if (selectedDimensionId === null) applyDefaultDhSelection();
		} else {
			applyDefaultDhSelection();
		}
	}

	function enterFmTab(): void {
		clearInspectorSelection();
		if (lastFmSnapshot) {
			restoreInspectorSnapshot(lastFmSnapshot);
			const hasAny =
				selectedMeasureId !== null ||
				selectedTreeMeasureGroupId !== null ||
				selectedTreeCubeId !== null ||
				selectedDimensionId !== null;
			if (!hasAny) applyDefaultFmSelection();
		} else {
			applyDefaultFmSelection();
		}
	}

	// Track the last PRIMARY tab so canvas / validate visits pass
	// through without disturbing the D&H ↔ F&M snapshot bookkeeping.
	// Starts null so the very first mount into a primary tab runs
	// enterDhTab / enterFmTab (subsuming the file's original one-shot
	// "snap to first dim on mount" behaviour, now generalised across
	// both tabs).
	let previousPrimaryTab: 'workbench' | 'facts' | null = null;
	$effect(() => {
		const mode = store.mode;
		if (mode !== 'workbench' && mode !== 'facts') return;
		if (mode === previousPrimaryTab) return;
		untrack(() => {
			if (previousPrimaryTab === 'workbench') lastDhSnapshot = captureInspectorSnapshot();
			else if (previousPrimaryTab === 'facts') lastFmSnapshot = captureInspectorSnapshot();
			if (mode === 'workbench') enterDhTab();
			else enterFmTab();
			previousPrimaryTab = mode;
		});
	});

	// ── Column drag source ──────────────────────────────────────────

	function handleColumnDragStart(
		e: DragEvent,
		table: SchemaCanvasTable,
		column: SchemaCanvasColumn
	) {
		if (!e.dataTransfer) return;
		const payload: ColumnDragPayload = {
			tableId: table.id,
			columnName: column.name,
			columnType: classifyColumn(column.sqlType)
		};
		e.dataTransfer.setData(COLUMN_MIME, JSON.stringify(payload));
		e.dataTransfer.effectAllowed = 'copy';
	}

	// readColumnPayload / isColumnDrag live in ./workbench-dnd (imported above).

	function handleTableDragStart(e: DragEvent, table: SchemaCanvasTable) {
		if (!e.dataTransfer) return;
		const payload: TableDragPayload = { tableId: table.id };
		e.dataTransfer.setData(TABLE_MIME, JSON.stringify(payload));
		e.dataTransfer.effectAllowed = 'copy';
	}
	// readTablePayload lives in ./workbench-dnd (imported above).

	function handleJoinGroupDragStart(e: DragEvent, row: JoinGroupRow) {
		if (!e.dataTransfer) return;
		// Map participating table NAMES back to ids — joinGroupsRail
		// stores names for display, but the drop handler needs ids.
		const tableIds = row.tableNames
			.map((name) => store.doc.tables.find((t) => t.name === name)?.id)
			.filter((id): id is string => typeof id === 'string');
		const payload: JoinGroupDragPayload = { key: row.key, tableIds };
		e.dataTransfer.setData(JOINGROUP_MIME, JSON.stringify(payload));
		e.dataTransfer.effectAllowed = 'copy';
	}
	// readJoinGroupPayload / isAnyWorkbenchDrag live in ./workbench-dnd
	// (imported above).

	// ── Center zone drops ───────────────────────────────────────────
	// We track which drop target is hovered so we can paint the
	// dashed primary border on JUST that one. A single $state ref
	// (kind + id) is enough — only one element can be the active
	// drop target at any moment.

	type DropTarget =
		| { kind: 'dim-zone' }
		| { kind: 'dim'; id: string }
		| { kind: 'hierarchy'; dimensionId: string; hierarchyId: string }
		| { kind: 'measure-zone' };

	let activeDropTarget = $state<DropTarget | null>(null);

	function dropTargetMatches(a: DropTarget | null, b: DropTarget): boolean {
		if (!a || a.kind !== b.kind) return false;
		if (a.kind === 'dim' && b.kind === 'dim') return a.id === b.id;
		if (a.kind === 'hierarchy' && b.kind === 'hierarchy') {
			return a.dimensionId === b.dimensionId && a.hierarchyId === b.hierarchyId;
		}
		return true;
	}

	function handleDragOver(e: DragEvent, target: DropTarget) {
		if (!isAnyWorkbenchDrag(e)) return;
		e.preventDefault();
		// `dragover` bubbles — the innermost target fires first, then
		// every ancestor does too.  Without stopPropagation the outer
		// dim ZONE would overwrite `activeDropTarget` after a nested
		// dim CARD set it, painting the giant zone-wide red ring
		// even though the user is targeting a specific card.  Stop the
		// bubble so the deepest match wins.
		e.stopPropagation();
		if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
		if (!dropTargetMatches(activeDropTarget, target)) activeDropTarget = target;
	}

	function handleDragLeave(e: DragEvent, target: DropTarget) {
		// Only clear when the cursor leaves the element entirely (not
		// when it crosses into a child).
		const related = e.relatedTarget as Node | null;
		const current = e.currentTarget as Node | null;
		if (related && current && current.contains(related)) return;
		if (dropTargetMatches(activeDropTarget, target)) activeDropTarget = null;
	}

	/** Walk a table's columns and append them as levels under the given
	 *  hierarchy.  Used by table + join-group drops so the user gets a
	 *  populated dim from one gesture; they can prune unwanted levels. */
	// eslint-disable-next-line @typescript-eslint/no-unused-vars
	function addAllColumnsAsLevels(
		dimensionId: string,
		hierarchyId: string,
		table: SchemaCanvasTable
	): void {
		for (const col of table.columns) {
			store.addLevel(dimensionId, hierarchyId, {
				tableId: table.id,
				columnName: col.name,
				type: classifyColumn(col.sqlType)
			});
		}
	}

	/** Create a dim from a table or join group.  Returns the dim but
	 *  does NOT auto-select it — selection happens only when the user
	 *  explicitly clicks a row in saved mode.  Auto-selecting during
	 *  multi-pick re-targets the Attributes pane on every checkbox
	 *  click, which feels broken when you're still composing the D set. */
	function createDimFromTable(table: SchemaCanvasTable): SchemaCanvasDimension {
		return store.addDimension({ name: table.name, tableId: table.id });
	}
	function createDimFromJoinGroup(group: JoinGroupRow): SchemaCanvasDimension {
		const dim = store.addDimension({ name: group.key, tableId: group.tableIds[0] });
		store.updateDimension(dim.id, { sourceJoinGroupKey: group.key });
		return dim;
	}

	function handleDimensionsZoneDrop(e: DragEvent) {
		activeDropTarget = null;
		e.preventDefault();
		// Scope of THIS handler: dimensions and their source binding ONLY.
		// Level / measure population happens elsewhere (the user will
		// define levels explicitly inside hierarchies once that part of
		// the UI is wired).  Column drops are no-ops here on purpose.
		const tablePayload = readTablePayload(e);
		if (tablePayload) {
			const table = store.doc.tables.find((t) => t.id === tablePayload.tableId);
			if (!table) return;
			const dim = store.addDimension({ name: table.name, tableId: table.id });
			focusDimId = dim.id;
			selectedDimensionId = dim.id;
			selectedHierarchyId = dim.hierarchies[0]?.id ?? null;
			return;
		}
		const groupPayload = readJoinGroupPayload(e);
		if (groupPayload) {
			const dim = store.addDimension({
				name: groupPayload.key,
				tableId: groupPayload.tableIds[0]
			});
			focusDimId = dim.id;
			selectedDimensionId = dim.id;
			selectedHierarchyId = dim.hierarchies[0]?.id ?? null;
		}
	}

	/** Resolve a hierarchy to host a new bulk drop (table or join group).
	 *
	 *  Mondrian convention (visible in Saiku's demo Sales cube): when a
	 *  hierarchy's name matches its parent dim's name, it's the IMPLICIT
	 *  DEFAULT — Saiku hides the hierarchy node and renders levels right
	 *  under the dim (Product, Performance Season Day).  When the names
	 *  differ, Saiku shows the hierarchy as an explicit node (Store/Stores,
	 *  Customer/Customers, Promotion/Media Type).
	 *
	 *  So:
	 *   - If there's an empty hier lying around (typically the implicit
	 *     default `addDimension` seeds with the dim's name), populate it
	 *     WITHOUT renaming.  Renaming would silently turn the implicit
	 *     default into an explicit hierarchy on export.
	 *   - Only a second/third drop creates a new EXPLICIT hier named
	 *     after the source — by convention these are the named alternates
	 *     ("Stores", "Calendar"/"Fiscal" on a Time dim, etc.). */
	// eslint-disable-next-line @typescript-eslint/no-unused-vars
	function claimOrCreateHierarchyForBulkDrop(
		dimension: SchemaCanvasDimension,
		sourceName: string
	): SchemaCanvasHierarchy | null {
		const empty = dimension.hierarchies.find((h) => h.levels.length === 0);
		if (empty) return empty;
		return store.addHierarchy(dimension.id, sourceName);
	}

	function handleDimensionDrop(e: DragEvent, dimension: SchemaCanvasDimension) {
		activeDropTarget = null;
		e.preventDefault();
		e.stopPropagation();
		// Scope: dim source binding ONLY.  Column drops are no-ops here
		// on purpose — levels are user-defined elsewhere once that part
		// of the UI is wired.  Table / join drop = bind primaryKeyTableId.
		const tablePayload = readTablePayload(e);
		if (tablePayload) {
			const table = store.doc.tables.find((t) => t.id === tablePayload.tableId);
			if (!table) return;
			store.updateDimension(dimension.id, { sourceTableId: table.id });
			selectedDimensionId = dimension.id;
			selectedHierarchyId = dimension.hierarchies[0]?.id ?? null;
			return;
		}
		const groupPayload = readJoinGroupPayload(e);
		if (groupPayload) {
			store.updateDimension(dimension.id, {
				sourceTableId: groupPayload.tableIds[0]
			});
			selectedDimensionId = dimension.id;
			selectedHierarchyId = dimension.hierarchies[0]?.id ?? null;
		}
	}

	function handleHierarchyDrop(e: DragEvent, dimension: SchemaCanvasDimension) {
		activeDropTarget = null;
		e.preventDefault();
		e.stopPropagation();
		// Scope: dim source binding ONLY (same as the dim-level handler).
		// Column drops on a hierarchy are no-ops on purpose.  Table /
		// join drop = bind the parent dim's primaryKeyTableId.
		const tablePayload = readTablePayload(e);
		if (tablePayload) {
			const table = store.doc.tables.find((t) => t.id === tablePayload.tableId);
			if (!table) return;
			store.updateDimension(dimension.id, { sourceTableId: table.id });
			return;
		}
		const groupPayload = readJoinGroupPayload(e);
		if (groupPayload) {
			store.updateDimension(dimension.id, {
				sourceTableId: groupPayload.tableIds[0]
			});
		}
	}

	function handleMeasuresZoneDrop(e: DragEvent) {
		activeDropTarget = null;
		const payload = readColumnPayload(e);
		if (!payload) return;
		e.preventDefault();
		// Measures default to sum.  Non-numeric drops still land,
		// just as `count` — the user picks the aggregator anyway.
		const aggregator: SchemaCanvasMeasure['aggregator'] = isNumericKind(payload.columnType)
			? 'sum'
			: 'count';
		store.addMeasure({
			tableId: payload.tableId,
			columnName: payload.columnName,
			aggregator
		});
	}

	// ── Inline rename helpers ────────────────────────────────────────
	// `oninput`-driven so every keystroke persists via the store
	// methods (which `touch()` + saveToStorage).

	function renameDimension(id: string, name: string) {
		store.updateDimension(id, { name });
	}

	function renameHierarchy(dimId: string, hierId: string, name: string) {
		store.updateHierarchy(dimId, hierId, { name });
	}
	/** Enforce sibling-unique hier name at commit time (blur).  Lets the
	 *  user type freely (no mid-keystroke suffixing) and only resolves
	 *  collisions when they leave the input.  Suffixes with "(2)", "(3)"
	 *  on collision. */
	function commitHierarchyName(dimId: string, hierId: string) {
		const d = store.dimensions.find((x) => x.id === dimId);
		const h = d?.hierarchies.find((x) => x.id === hierId);
		if (!d || !h) return;
		const desired = h.name.trim();
		const siblings = d.hierarchies.filter((x) => x.id !== hierId).map((x) => x.name);
		if (!siblings.includes(desired)) return;
		let n = 2;
		while (siblings.includes(`${desired} (${n})`)) n++;
		store.updateHierarchy(dimId, hierId, { name: `${desired} (${n})` });
	}
	/** Same idea for dim names — collision-free at the cube level. */
	function commitDimensionName(dimId: string) {
		const d = store.dimensions.find((x) => x.id === dimId);
		if (!d) return;
		const desired = d.name.trim();
		const siblings = store.dimensions.filter((x) => x.id !== dimId).map((x) => x.name);
		if (!siblings.includes(desired)) return;
		let n = 2;
		while (siblings.includes(`${desired} (${n})`)) n++;
		store.updateDimension(dimId, { name: `${desired} (${n})` });
	}

	function renameLevel(dimId: string, hierId: string, levelId: string, name: string) {
		store.updateLevel(dimId, hierId, levelId, { name });
	}

	function renameMeasure(measureId: string, name: string) {
		store.updateMeasure(measureId, { name });
	}

	// Look up a level's source table name for the badge that reads
	// "from Customer" next to a level row.
	function tableNameFor(tableId: string): string {
		return store.doc.tables.find((t) => t.id === tableId)?.name ?? tableId;
	}
	// Look up the SQL type (VARCHAR, INT2, …) for a (tableId, columnName)
	// pair — used to surface type chips next to attribute / level rows
	// so the user gets the same info they see on the schema canvas.
	function columnTypeFor(tableId: string, columnName: string): string | undefined {
		return store.doc.tables
			.find((t) => t.id === tableId)
			?.columns.find((c) => c.name === columnName)?.sqlType;
	}

	const AGGREGATORS: SchemaCanvasMeasure['aggregator'][] = [
		'sum',
		'count',
		'avg',
		'min',
		'max',
		'distinct-count',
		'median',
		'percentile'
	];

	// ── Right rail: read-only joins summary ──────────────────────────
	// One line per join, grouped under the user-renamed label so it
	// matches what Step 1 shows. Lets the workbench user see "Region
	// is reachable via customer.region_id ↔ region.id" without
	// flipping tabs.

	interface JoinGroupSummary {
		label: string;
		joins: Array<{
			id: string;
			fromTable: string;
			fromColumn: string;
			toTable: string;
			toColumn: string;
		}>;
	}

	// eslint-disable-next-line @typescript-eslint/no-unused-vars
	const joinGroups = $derived.by<JoinGroupSummary[]>(() => {
		const tableName = (id: string) => store.doc.tables.find((t) => t.id === id)?.name ?? id;
		const buckets = new Map<string, JoinGroupSummary>();
		for (const j of store.doc.joins) {
			const label = store.joinGroupLabelFor(j);
			const entry = buckets.get(label) ?? { label, joins: [] };
			entry.joins.push({
				id: j.id,
				fromTable: tableName(j.sourceTableId),
				fromColumn: j.sourceColumnName,
				toTable: tableName(j.targetTableId),
				toColumn: j.targetColumnName
			});
			buckets.set(label, entry);
		}
		return Array.from(buckets.values()).sort((a, b) => a.label.localeCompare(b.label));
	});

	// Quick stats for pane-header eyebrows.  dimensionCount is used from
	// paneHeaderContext(kind='dimensions') via store.dimensions.length —
	// no dedicated derived for it since the paneHeader reads the array
	// directly.  Attribute / hierarchy / level counts feed the other
	// eyebrow lines; measureCount feeds the tree-view stat block.
	const attributeCount = $derived(
		store.dimensions.reduce((n, d) => n + (d.attributes ?? []).length, 0)
	);
	const hierarchyCount = $derived(store.dimensions.reduce((n, d) => n + d.hierarchies.length, 0));
	const levelCount = $derived(
		store.dimensions.reduce((n, d) => n + d.hierarchies.reduce((m, h) => m + h.levels.length, 0), 0)
	);
	const measureCount = $derived(store.measures.length);

	// Border colour helper for the drop-target ring.  Resting state is
	// `border-transparent` so the dashed outline disappears when nothing
	// is being dragged — the giant always-on dashed rectangles read as
	// visual noise.  Active drag-over goes `border-primary`; idle-while-
	// a-drag-is-happening goes `border-border` so the user can see WHICH
	// zone is hover-able vs which is currently the target.
	const isDragging = $derived(activeDropTarget !== null);
	function dropRingFor(target: DropTarget): string {
		if (dropTargetMatches(activeDropTarget, target)) return 'border-primary';
		return isDragging ? 'border-border' : 'border-transparent';
	}
</script>

<div class="flex h-full min-h-0 flex-1 overflow-hidden" data-testid="canvas-workbench-view">
	{#if store.mode === 'validate'}
		<ConfirmCubePane
			{store}
			{cubes}
			{selectedCubeId}
			{selectedCube}
			{factsMeasureGroups}
			{isSchemaSaved}
			{isSchemaDirty}
			refreshSignal={confirmCubeRefreshTs}
			bind:validateTab
			bind:modelViewMode
			bind:modelResetTs
			bind:outlineCollapsed
			{outlineCollapsedSections}
			{switchCube}
			{toggleOutlineSection}
			sampleDataTab={inspectorSampleData}
			tryQueryTab={inspectorTryQuery}
		/>
	{:else}
		<!-- ── LEFT RAIL: two stacked panes (join groups + tables) ───────
	     Only shown in Cards view, where the centre is dim-card workspace
	     and a tables/joins rail provides drop sources.  Hidden in Columns
	     mode (source picker is in the Dimensions pane) and in Tree mode
	     (tree already shows Shared Dimensions + Cubes; a duplicate joins
	     rail just adds noise). -->
		{#if viewMode === 'cards'}
			<aside
				class="flex w-72 shrink-0 flex-col border-r bg-card"
				style:border-color="hsl(var(--border))"
				aria-label="Tables on canvas"
				data-testid="workbench-tables-rail"
			>
				<!-- ─ COLUMN-LEVEL SEARCH ─ -->
				<!-- Search lives at the very top of the rail (above both pane
		     headers) so it filters across Join groups + Tables + columns
		     without the user hunting for it inside a collapsible. -->
				{#if store.doc.tables.length > 0}
					<div class="shrink-0 border-b px-2 py-2" style:border-color="hsl(var(--border))">
						<div class="relative">
							<Search
								class="pointer-events-none absolute top-1/2 left-2 h-3 w-3 -translate-y-1/2 opacity-50"
								aria-hidden="true"
							/>
							<input
								type="search"
								bind:value={tableFilterQuery}
								placeholder="Filter tables or columns…"
								aria-label="Filter tables or columns"
								class="h-7 w-full rounded border bg-background py-1 pr-2 pl-7 text-xs focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
								style:border-color="hsl(var(--border))"
								data-testid="workbench-table-filter-input"
							/>
						</div>
					</div>
				{/if}

				<!-- ─ TOP PANE: read-only join groups from Step 1 ─ -->
				<section
					class="flex shrink-0 flex-col border-b"
					style:border-color="hsl(var(--border))"
					data-testid="workbench-joingroups-pane"
				>
					<button
						type="button"
						onclick={() => (railJoinGroupsCollapsed = !railJoinGroupsCollapsed)}
						class="flex w-full items-center gap-1.5 border-b px-3 py-2 text-left hover:bg-accent/40"
						style:border-color="hsl(var(--border))"
						aria-expanded={!railJoinGroupsCollapsed}
						aria-controls="workbench-joingroups-body"
					>
						<div class="min-w-0 flex-1">
							<div
								class="text-[10px] font-medium tracking-wider uppercase"
								style:color="hsl(var(--muted-foreground))"
							>
								Join groups
							</div>
							<div class="mt-0.5 text-[11px] text-muted-foreground">
								{joinGroupsRail.length}
								{joinGroupsRail.length === 1 ? 'group' : 'groups'} · read-only
							</div>
						</div>
						{#if railJoinGroupsCollapsed}
							<ChevronRight class="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />
						{:else}
							<ChevronDown class="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />
						{/if}
					</button>

					{#if !railJoinGroupsCollapsed}
						<div
							id="workbench-joingroups-body"
							class="max-h-48 shrink-0 overflow-y-auto bg-background"
						>
							{#if joinGroupsRail.length === 0}
								<div class="p-3">
									<p
										class="rounded border border-dashed p-2 text-[11px]"
										style:border-color="hsl(var(--border))"
										style:color="hsl(var(--muted-foreground))"
									>
										No joins wired yet. Flip back to Schema Canvas to draw some.
									</p>
								</div>
							{:else}
								<ul>
									{#each joinGroupsRail as g (g.key)}
										{@const isRowCollapsed = collapsedJoinGroupRows.has(g.key)}
										<li
											class="flex flex-wrap border-b last:border-b-0"
											style:border-color="hsl(var(--border))"
											data-testid="workbench-joingroup-row"
										>
											<button
												type="button"
												onclick={() => toggleJoinGroupRow(g.key)}
												draggable="true"
												ondragstart={(e) => handleJoinGroupDragStart(e, g)}
												class="flex flex-1 cursor-grab items-center gap-1.5 px-3 py-1.5 text-left text-xs hover:bg-accent/40 active:cursor-grabbing"
												title="Click to expand member tables, drag to bind a dimension"
											>
												{#if isRowCollapsed}
													<ChevronRight class="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />
												{:else}
													<ChevronDown class="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />
												{/if}
												<Layers class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
												<span class="truncate font-medium">{g.label}</span>
												<span class="ml-auto text-[10px] opacity-60">{g.tableNames.length}</span>
											</button>
											<!-- Click-to-create-dim from this join group -->
											<button
												type="button"
												onclick={() => createDimFromJoinGroup(g)}
												class="shrink-0 px-2 text-muted-foreground hover:bg-accent/40 hover:text-foreground"
												aria-label="Create dimension from this join group"
												title="Create dimension bound to this join group"
												data-testid="workbench-joingroup-create-dim"
											>
												<Layers class="h-3 w-3" aria-hidden="true" />
											</button>
											{#if !isRowCollapsed}
												<ul class="pr-3 pb-1 pl-7">
													{#each g.tableNames as name, idx (`${name}::${idx}`)}
														<li
															class="truncate py-0.5 font-mono text-[11px]"
															style:color="hsl(var(--muted-foreground))"
														>
															{name}
														</li>
													{/each}
												</ul>
											{/if}
										</li>
									{/each}
								</ul>
							{/if}
						</div>
					{/if}
				</section>

				<!-- ─ BOTTOM PANE: tables + columns (drag sources) ─ -->
				<section class="flex min-h-0 flex-1 flex-col" data-testid="workbench-tables-pane">
					<button
						type="button"
						onclick={() => (railTablesCollapsed = !railTablesCollapsed)}
						class="flex w-full items-center gap-1.5 border-b px-3 py-2 text-left hover:bg-accent/40"
						style:border-color="hsl(var(--border))"
						aria-expanded={!railTablesCollapsed}
						aria-controls="workbench-tables-body"
					>
						<div class="min-w-0 flex-1">
							<div
								class="text-[10px] font-medium tracking-wider uppercase"
								style:color="hsl(var(--muted-foreground))"
							>
								Tables
							</div>
							<div class="mt-0.5 text-[11px] text-muted-foreground">
								{store.doc.tables.length}
								{store.doc.tables.length === 1 ? 'table' : 'tables'} · drag columns
							</div>
						</div>
						{#if railTablesCollapsed}
							<ChevronRight class="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />
						{:else}
							<ChevronDown class="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />
						{/if}
					</button>

					{#if !railTablesCollapsed}
						<div id="workbench-tables-body" class="min-h-0 flex-1 overflow-y-auto bg-background">
							{#if store.doc.tables.length === 0}
								<div class="p-3">
									<div
										class="rounded border border-dashed p-3 text-center text-xs"
										style:border-color="hsl(var(--border))"
										style:color="hsl(var(--muted-foreground))"
									>
										No tables yet — flip back to <span class="font-medium text-foreground"
											>Schema Canvas</span
										> to add some.
									</div>
								</div>
							{:else if filteredTables.length === 0}
								<div class="p-3">
									<div
										class="rounded border border-dashed p-3 text-center text-xs"
										style:border-color="hsl(var(--border))"
										style:color="hsl(var(--muted-foreground))"
									>
										No matches for <span class="font-mono text-foreground">{tableFilterQuery}</span
										>.
									</div>
								</div>
							{:else}
								<ul>
									{#each filteredTables as t (t.id)}
										{@const isCollapsed =
											collapsedTables.has(t.id) && !forceOpenForFilter.has(t.id)}
										<li
											class="flex border-b"
											style:border-color="hsl(var(--border))"
											data-testid="workbench-table-row"
										>
											<button
												type="button"
												onclick={() => toggleTableCollapsed(t.id)}
												draggable="true"
												ondragstart={(e) => handleTableDragStart(e, t)}
												class="flex flex-1 cursor-grab items-center gap-1.5 px-3 py-1.5 text-left text-xs hover:bg-accent/40 active:cursor-grabbing"
												title="Click to expand columns, drag to bind a dimension"
											>
												{#if isCollapsed}
													<ChevronRight class="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />
												{:else}
													<ChevronDown class="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />
												{/if}
												<Database class="h-3 w-3 shrink-0 opacity-70" aria-hidden="true" />
												<span class="truncate font-mono">
													{#if t.schema}<span class="opacity-60">{t.schema}.</span>{/if}{t.name}
												</span>
												<span class="ml-auto text-[10px] opacity-60">{t.columns.length}</span>
											</button>
											<!-- Click-to-create-dim: makes a dim bound to this T -->
											<button
												type="button"
												onclick={() => createDimFromTable(t)}
												class="shrink-0 px-2 text-muted-foreground hover:bg-accent/40 hover:text-foreground"
												aria-label="Create dimension from this table"
												title="Create dimension bound to this table"
												data-testid="workbench-table-create-dim"
											>
												<Layers class="h-3 w-3" aria-hidden="true" />
											</button>
											{#if !isCollapsed}
												<ul class="pb-1">
													{#each t.columns.filter( (c) => columnMatchesFilter(c.name) ) as col (col.name)}
														{@const kind = classifyColumn(col.sqlType)}
														<li
															draggable="true"
															ondragstart={(e) => handleColumnDragStart(e, t, col)}
															class="group flex cursor-grab items-center gap-1.5 px-3 py-1 pl-7 text-[11px] hover:bg-accent/40 active:cursor-grabbing"
															data-testid="workbench-column-row"
															data-table-id={t.id}
															data-column-name={col.name}
														>
															<GripVertical
																class="h-3 w-3 shrink-0 opacity-30 group-hover:opacity-70"
																aria-hidden="true"
															/>
															<span class="truncate font-mono">{col.name}</span>
															<span
																class="ml-auto shrink-0 font-mono text-[9px] tracking-wide uppercase opacity-50"
																title={col.sqlType}
															>
																{kind ?? col.sqlType}
															</span>
														</li>
													{/each}
												</ul>
											{/if}
										</li>
									{/each}
								</ul>
							{/if}
						</div>
					{/if}
				</section>
			</aside>
		{/if}

		<!-- ── CENTER: dimensions + measures authoring ─────────────────── -->
		<div class="flex h-full min-h-0 flex-1 flex-col overflow-hidden bg-elev-0">
			<!-- Sub-header removed: the "Schema" title, the Columns/Tree view
		     toggle, and the aggregate `N dimensions · N levels · N measures`
		     block all landed on the pane headers (dims/attrs/hier show
		     their own counts, F&M shows its own measures count, and the
		     tab bar drives the surface).  Tree view will move to the
		     `Confirm cube` tab as a schema outline. -->

			<!-- Single-column flex layout — the second 360px grid column that
		     used to host the inline Measures rail is gone (Measures moved
		     to the outer aside for Cards/Tree, and becomes a pane in
		     Columns mode), so the dimensions area now uses the full
		     width and isn't constrained by a phantom reserved column. -->
			<!-- No inner padding — panes render flush to the page shell edges,
		     matching how Schema Canvas fills its own container. -->
			<div class="flex min-h-0 flex-1 flex-col gap-3 overflow-auto">
				<!-- DIMENSIONS ZONE -->
				<section
					class="flex h-full min-h-0 flex-1 flex-col gap-2"
					data-testid="workbench-dimensions-zone"
				>
					<!-- Columns mode has no outer section title (each pane owns
				     its own header), so the title row was rendering as an
				     empty 0-height row + a gap-2 that ate 8px above the
				     panes.  Guard the whole row on viewMode !== 'columns'. -->
					{#if viewMode !== 'columns'}
						<div class="flex items-center justify-between gap-2">
							<!-- Tree focus toggle — picks which root branch fills the
						     column.  The schema has TWO concerns that don't nest
						     under each other (shared dimensions on one side, cubes
						     with their MGs on the other), so a single "DIMENSIONS"
						     header was misleading. -->
							<div class="flex flex-wrap items-center gap-2">
								<div
									class="inline-flex overflow-hidden rounded border"
									style:border-color="hsl(var(--border))"
									role="group"
									aria-label="Tree focus"
									data-testid="workbench-tree-focus-toggle"
								>
									<button
										type="button"
										onclick={() => (treeFocus = 'dims')}
										aria-pressed={treeFocus === 'dims'}
										class="inline-flex items-center gap-1 px-2.5 py-1 text-[11px] font-semibold tracking-wide uppercase transition-colors {treeFocus ===
										'dims'
											? 'bg-primary text-primary-foreground'
											: 'text-muted-foreground hover:bg-accent/40'}"
										data-testid="workbench-tree-focus-dims"
									>
										<Layers class="h-3 w-3" aria-hidden="true" />
										Dimensions &amp; Hierarchies
									</button>
									<button
										type="button"
										onclick={() => (treeFocus = 'cubes')}
										aria-pressed={treeFocus === 'cubes'}
										class="inline-flex items-center gap-1 border-l px-2.5 py-1 text-[11px] font-semibold tracking-wide uppercase transition-colors {treeFocus ===
										'cubes'
											? 'bg-primary text-primary-foreground'
											: 'text-muted-foreground hover:bg-accent/40'}"
										style:border-color="hsl(var(--border))"
										data-testid="workbench-tree-focus-cubes"
									>
										<Sigma class="h-3 w-3" aria-hidden="true" />
										Cube Facts &amp; Measures
									</button>
								</div>
								{#if treeFocus === 'dims'}
									<Button
										size="sm"
										variant="ghost"
										onclick={() => addDimensionAndFocus()}
										data-testid="workbench-add-dimension"
									>
										<Plus class="h-3 w-3" aria-hidden="true" />
										Add dimension
									</Button>
								{:else}
									<Button
										size="sm"
										variant="ghost"
										onclick={() => {
											const fresh = addCube();
											selectedTreeCubeId = fresh.id;
											selectedTreeMeasureGroupId = null;
										}}
										data-testid="workbench-tree-add-cube"
									>
										<Plus class="h-3 w-3" aria-hidden="true" />
										New cube
									</Button>
								{/if}
							</div>
							<!-- Tree / Columns toggle moved to the top "Dimensions
						     Workbench" header bar (centered) so this section
						     toolbar reads cleaner. -->
						</div>
					{/if}

					{#if viewMode === 'columns'}
						<!-- Columns view layout: a horizontal split.  On the left,
					     a vertical stack of two collapsible sections —
					     Dimensions (Create Your Dimensions & Hierarchies)
					     on top and Facts (Fact & Measures + Measure Groups
					     + Calculated Measures) underneath — separated by a
					     drag-resize handle.  On the right, an Inspector
					     drawer with Properties / Code tabs that collapses
					     to a thin vertical strip.
					     Each of the three regions can be hidden so the
					     user focuses on whatever they're editing without
					     leaving this screen. -->
						<!-- Layout axis depends on the Inspector's docking side.
					     `bottom` → flex-col (drawer sits under the panes).
					     `right`  → flex-row (drawer sits to the right of them). -->
						<div
							class="flex h-full min-h-0 gap-2 {inspectorPosition === 'bottom'
								? 'flex-col'
								: 'flex-row'}"
						>
							<!-- LEFT MAIN STACK: Dimensions on top, Facts below.
						     Hidden when the inspector is maximized so the
						     drawer's content (Sample data table / Try a query
						     result) takes over the whole workbench area. -->
							{#if !inspectorMaximized}
								<div bind:this={workbenchBodyEl} class="flex min-h-0 min-w-0 flex-1 flex-col gap-2">
									<!-- Dim body renders ONLY in the workbench tab.  In `facts`
								     (or `validate`) mode the top-level tab bar drives
								     what's showing — collapsed strip within this view
								     would be confusing extra chrome. -->
									{#if store.mode !== 'workbench'}
										<!-- Nothing — dim section belongs to another tab. -->
									{:else if dimSectionCollapsed}
										<button
											type="button"
											onclick={() => (dimSectionCollapsed = false)}
											class="flex shrink-0 items-center justify-between gap-2 rounded border bg-elev-2 px-3 py-1.5 text-[11px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent/40"
											style:border-color="hsl(var(--border))"
											data-testid="workbench-dim-section-expand"
											title="Expand dimensions area"
										>
											<ChevronDown class="h-3 w-3 shrink-0" aria-hidden="true" />
											<span class="flex-1 text-left">Create Your Dimensions &amp; Hierarchies</span>
										</button>
									{:else}
										<!-- Section frame chrome removed — the title sits
								     directly above the content as a small eyebrow,
								     no rounded border / bg-elev-2 wrap. -->
										<!-- Section eyebrow + collapse chevron removed — the step
									     indicator above the tab bar already tells the user
									     they're on Dimensions & Hierarchies. -->
										<div
											class="flex min-h-0 flex-col"
											style:flex={factsSectionCollapsed ? '1 1 0' : `${dimFactsFraction} 1 0`}
										>
											<div class="flex min-h-0 flex-1 overflow-hidden">
												<DimensionsHierarchiesPane
													{store}
													{columnSlots}
													{paneDragIndex}
													{paneDragOverIndex}
													{dimSortKey}
													{dimSortDir}
													{attributeCount}
													{hierarchyCount}
													{levelCount}
													{selectedDimension}
													{selectedHierarchy}
													{joinGroupsRail}
													{PANE_KIND_ICONS}
													{PANE_KIND_LABELS}
													bind:attributesPaneMode
													bind:contentDragOverHierarchyId
													bind:deletingDimId
													bind:dimDragOverHierName
													bind:dimensionPaneFilter
													bind:dimensionPaneMode
													bind:dimensionPreviewKey
													bind:editingDimNameId
													bind:editingHierNameId
													bind:focusDimId
													bind:hierarchyExplicitlyClicked
													bind:manualAddDimName
													bind:manualAddDimOpen
													bind:manualAddDimSourceKey
													bind:selectedAttributeKey
													bind:selectedLevelId
													bind:selectedDimensionId
													bind:selectedHierarchyId
													bind:selectedMeasureId
													{focus}
													{isFocused}
													{isContext}
													{autoFocus}
													{columnTypeFor}
													{dropRingFor}
													{addHierarchyFromAttribute}
													{commitDimensionName}
													{commitHierarchyName}
													{createDimFromJoinGroup}
													{createDimFromTable}
													{handleAttributeDragStart}
													{handleDimensionDrop}
													{handleDragLeave}
													{handleDragOver}
													{handlePaneDragEnd}
													{handlePaneDragLeave}
													{handlePaneDragOver}
													{handlePaneDragStart}
													{handlePaneDrop}
													{renameDimension}
													{renameHierarchy}
													{requestDeleteHierarchy}
													{resetManualAddDim}
													{toggleDimSort}
												/>
											</div>
										</div>
									{/if}
									<!-- Vertical resize handle between Dim and Facts.
							     Only useful when both sections are expanded —
							     otherwise the open one takes the full height. -->
									{#if !dimSectionCollapsed && !factsSectionCollapsed}
										<div
											role="separator"
											aria-orientation="horizontal"
											aria-label="Resize dimensions / facts split"
											onpointerdown={handleDimFactsResizeStart}
											class="workbench-resize-handle flex h-3 shrink-0 cursor-row-resize items-center justify-center rounded {dimFactsResizing
												? 'workbench-resize-handle-active'
												: ''}"
											data-testid="workbench-dim-facts-resize"
											title="Drag to resize"
										>
											<div class="h-1 w-16 rounded-full"></div>
										</div>
									{/if}
									<!-- Top-level tab drives mode; facts body renders ONLY in
								     the `facts` tab.  The collapsed-strip button used to
								     appear when a user closed the facts pane from within
								     the workbench view — with the tab bar that path is
								     gone, so we skip the strip entirely in wrong-mode. -->
									{#if store.mode !== 'facts'}
										<!-- Nothing — the facts section belongs to another tab. -->
									{:else if factsSectionCollapsed}
										<button
											type="button"
											onclick={() => (factsSectionCollapsed = false)}
											class="flex shrink-0 items-center justify-between gap-2 rounded border bg-elev-2 px-3 py-1.5 text-[11px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent/40"
											style:border-color="hsl(var(--border))"
											data-testid="workbench-facts-section-expand"
											title="Expand facts area"
										>
											<ChevronUp class="h-3 w-3 shrink-0" aria-hidden="true" />
											<span class="flex-1 text-left"
												>Facts &amp; Measures — {selectedCube?.name ?? 'Cube'}</span
											>
										</button>
									{:else}
										<!-- gap-3 matches the horizontal gap between Measure Groups
									     and Calculations below so vertical and horizontal
									     breathing room stay symmetric. -->
										<div class="flex min-h-0 flex-1 flex-col gap-3">
											<!-- Full-width Cube picker panel — its own bordered
										     surface at the top of the tab so it reads as "you
										     are choosing a cube to work on".  Matches the pane
										     chrome (rounded border, header eyebrow, body). -->
											<section
												class="flex shrink-0 flex-col rounded-md border bg-elev-1"
												style:border-color="hsl(var(--border))"
												data-testid="workbench-cube-picker-panel"
											>
												<header
													class="flex shrink-0 items-center gap-2 border-b bg-elev-1 px-2.5 py-1.5"
													style:border-color="hsl(var(--border))"
												>
													<Box class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
													<span class="text-[11px] font-semibold tracking-wider uppercase">
														Cube
													</span>
													<span
														class="truncate text-[10px]"
														style:color="hsl(var(--muted-foreground))"
													>
														— pick a cube to see its facts and measures below
													</span>
													<!-- Header-right actions: "New cube" and "Edit permissions".
												     Both trigger the expanded 2-column view (right side
												     hosts either the new-cube form or the selected cube's
												     permissions).  Keeping the picker row below tight. -->
													<div class="ml-auto flex shrink-0 items-center gap-1">
														<button
															type="button"
															onclick={() => {
																cubePermissionsOpen = true;
																cubeNewFormOpen = true;
																cubeNewFormName = '';
																cubeNewFormVisibility = 'workspace';
																cubeNewFormGroups = '';
															}}
															class="inline-flex items-center gap-1 rounded border px-2 py-0.5 text-[10px] font-medium text-primary hover:bg-primary/10"
															style:border-color="hsl(var(--primary))"
															data-testid="workbench-cube-header-new"
															title="Create a new cube"
														>
															<Plus class="h-3 w-3" aria-hidden="true" />
															New cube
														</button>
														<button
															type="button"
															onclick={() => {
																cubePermissionsOpen = !cubePermissionsOpen;
																cubeNewFormOpen = false;
															}}
															class="inline-flex items-center gap-1 rounded border px-2 py-0.5 text-[10px] font-medium text-muted-foreground hover:bg-accent/40"
															style:border-color="hsl(var(--border))"
															data-testid="workbench-cube-header-edit-perms"
															title="Cube properties"
														>
															<Lock class="h-3 w-3" aria-hidden="true" />
															Cube properties
														</button>
													</div>
												</header>
												{#if !cubePermissionsOpen}
													<!-- Compact mode: just the picker.  Actions moved to
												     the header top-right. -->
													<div class="p-2">
														{@render cubeSwitcher()}
													</div>
												{:else}
													<!-- Expanded mode: 2-column layout.  LEFT = scrollable
												     list of cubes; RIGHT = details (name +
												     permissions) for the selected cube OR the inline
												     new-cube form when the user hit "New cube". -->
													<div
														class="flex min-h-[240px] flex-row gap-3 border-t p-2"
														style:border-color="hsl(var(--border))"
														data-testid="workbench-cube-manage"
													>
														<!-- LEFT: cube list + "+ New cube" at the bottom. -->
														<div
															class="flex w-40 shrink-0 flex-col gap-1 rounded border bg-elev-2"
															style:border-color="hsl(var(--border))"
														>
															<div
																class="shrink-0 border-b px-2 py-1 text-[10px] font-semibold tracking-wider uppercase"
																style:border-color="hsl(var(--border))"
																style:color="hsl(var(--muted-foreground))"
															>
																Cubes · {cubes.length}
															</div>
															<div
																class="flex min-h-0 flex-1 flex-col overflow-y-auto"
																data-testid="workbench-cube-manage-list"
															>
																{#each cubes as c (c.id)}
																	{@const active = c.id === selectedCubeId && !cubeNewFormOpen}
																	<button
																		type="button"
																		onclick={() => {
																			switchCube(c.id);
																			cubeNewFormOpen = false;
																		}}
																		class="flex items-center justify-between gap-2 border-b px-2 py-1.5 text-left text-[11px] last:border-b-0 {active
																			? 'bg-primary/10 font-semibold text-foreground'
																			: 'text-muted-foreground hover:bg-accent/40'}"
																		style:border-color="hsl(var(--border))"
																		data-testid="workbench-cube-manage-item"
																		data-cube-id={c.id}
																	>
																		<span class="truncate">{c.name || 'Untitled cube'}</span>
																		{#if active}
																			<span class="shrink-0 text-primary">●</span>
																		{/if}
																	</button>
																{/each}
															</div>
															<button
																type="button"
																onclick={() => {
																	cubeNewFormOpen = true;
																	cubeNewFormName = '';
																	cubeNewFormVisibility = 'workspace';
																	cubeNewFormGroups = '';
																}}
																class="flex shrink-0 items-center gap-1 border-t px-2 py-1.5 text-left text-[11px] font-medium text-primary hover:bg-primary/10"
																style:border-color="hsl(var(--border))"
																data-testid="workbench-cube-picker-new"
															>
																<Plus class="h-3 w-3" aria-hidden="true" />
																New cube
															</button>
														</div>
														<!-- RIGHT: details / new-cube form. -->
														<div
															class="flex min-w-0 flex-1 flex-col gap-2 overflow-y-auto"
															data-testid="workbench-cube-manage-detail"
														>
															{#if cubeNewFormOpen}
																<div
																	class="flex flex-col gap-2"
																	data-testid="workbench-cube-new-form"
																>
																	<label class="flex flex-col gap-1 text-[11px]">
																		<span
																			class="text-[10px] font-semibold tracking-wider uppercase"
																			style:color="hsl(var(--muted-foreground))"
																		>
																			Cube name
																		</span>
																		<input
																			type="text"
																			bind:value={cubeNewFormName}
																			placeholder="e.g. Sales"
																			class="h-7 rounded border bg-background px-2 text-[11px] focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
																			style:border-color="hsl(var(--border))"
																			data-testid="workbench-cube-new-name"
																		/>
																	</label>
																	{@render permissionsFieldset('workbench-cube-new')}
																	<div class="flex items-center justify-end gap-2 pt-1">
																		<button
																			type="button"
																			onclick={() => (cubeNewFormOpen = false)}
																			class="rounded px-3 py-1 text-[11px] text-muted-foreground hover:bg-accent/40"
																			data-testid="workbench-cube-new-cancel"
																		>
																			Cancel
																		</button>
																		<button
																			type="button"
																			onclick={() => {
																				const fresh = addCube();
																				if (cubeNewFormName.trim()) {
																					renameCube(fresh.id, cubeNewFormName.trim());
																				}
																				switchCube(fresh.id);
																				cubeNewFormOpen = false;
																			}}
																			class="inline-flex items-center gap-1 rounded bg-primary px-3 py-1 text-[11px] font-medium text-primary-foreground hover:bg-primary/90"
																			data-testid="workbench-cube-new-create"
																		>
																			<Plus class="h-3 w-3" aria-hidden="true" />
																			Create cube
																		</button>
																	</div>
																</div>
															{:else if selectedCube}
																<label class="flex flex-col gap-1 text-[11px]">
																	<span
																		class="text-[10px] font-semibold tracking-wider uppercase"
																		style:color="hsl(var(--muted-foreground))"
																	>
																		Cube name
																	</span>
																	<input
																		type="text"
																		value={selectedCube.name}
																		oninput={(e) =>
																			renameCube(selectedCube!.id, e.currentTarget.value)}
																		placeholder="Cube name"
																		class="h-7 rounded border bg-background px-2 text-[11px] focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
																		style:border-color="hsl(var(--border))"
																		data-testid="workbench-cube-manage-name"
																	/>
																</label>
																<div
																	class="mt-1 flex items-center gap-1 text-[10px]"
																	style:color="hsl(var(--muted-foreground))"
																>
																	<Lock class="h-3 w-3" aria-hidden="true" />
																	Permissions — draft, not yet wired
																</div>
																{@render permissionsFieldset('workbench-cube-existing')}
															{:else}
																<p class="text-[11px] text-muted-foreground">
																	Pick a cube on the left, or hit “New cube” to add one.
																</p>
															{/if}
														</div>
													</div>
													<button
														type="button"
														onclick={() => {
															cubePermissionsOpen = false;
															cubeNewFormOpen = false;
														}}
														class="flex shrink-0 items-center justify-center gap-1 border-t px-2 py-1 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent/40"
														style:border-color="hsl(var(--border))"
														data-testid="workbench-cube-manage-close"
													>
														<ChevronUp class="h-3 w-3" aria-hidden="true" />
														Collapse
													</button>
												{/if}
											</section>
											<!-- F&M body: MG + Add Calc panes fill the rest. -->
											<div class="flex min-h-0 flex-1 overflow-hidden">
												<div class="flex h-full min-h-0 w-full flex-row gap-3">
													<FactsMeasuresPane
														{store}
														{factsSlots}
														{factsPaneDragIndex}
														{factsPaneDragOverIndex}
														{AGGREGATORS}
														{ALLOW_GROUP_DROP_ON_CALC}
														{factsSelectedMeasures}
														bind:factsEditMode
														bind:factsSelectedTableId
														bind:factsTableSearch
														bind:factsTableConfirmed
														bind:factsMeasureGroups
														bind:factsSelectedGroupId
														bind:factsEditingMeasureGroupId
														bind:focusGroupId
														bind:factsCalcs
														bind:factsEditingCalcId
														bind:editingMeasureCaption
														{selectMgMeasure}
														{selectMgDimension}
														{selectFactsCalc}
														{handleFactsPaneDragStart}
														{handleFactsPaneDragOver}
														{handleFactsPaneDragLeave}
														{handleFactsPaneDrop}
														{handleFactsPaneDragEnd}
														{addFactsMeasureGroup}
														{removeFactsMeasureGroup}
														{renameFactsMeasureGroup}
														{addFactsCalc}
														{removeFactsCalc}
														{renameFactsCalc}
														{addCalcMeasure}
														{addCalcOperator}
														{popCalcToken}
														{clearCalcTokens}
														{setCalcMode}
														{updateCalcFormula}
														{calcNeedsMeasure}
														{formatCalcOp}
														{commitMeasureCaption}
														{focus}
														{isFocused}
														{autoFocus}
													/>
												</div>
											</div>
										</div>
									{/if}
								</div>
							{/if}
							<!-- INSPECTOR DRAWER.  Orientation follows `inspectorPosition`:
						       - bottom → horizontal strip / row-drag resize / height
						       - right  → vertical strip / col-drag resize / width
						     Chevrons flip Up/Down vs Left/Right accordingly. -->
							{#if inspectorCollapsed}
								{#if inspectorPosition === 'bottom'}
									<button
										type="button"
										onclick={() => (inspectorCollapsed = false)}
										class="flex h-8 w-full shrink-0 items-center justify-between gap-2 rounded border bg-elev-2 px-2 py-1 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent/40"
										style:border-color="hsl(var(--border))"
										data-testid="workbench-inspector-drawer-expand"
										title="Open inspector drawer"
									>
										<ChevronUp class="h-3 w-3" aria-hidden="true" />
										<span>Inspector</span>
										<ChevronUp class="h-3 w-3 opacity-0" aria-hidden="true" />
									</button>
								{:else}
									<button
										type="button"
										onclick={() => (inspectorCollapsed = false)}
										class="flex h-full w-8 shrink-0 flex-col items-center justify-start gap-2 rounded border bg-elev-2 px-1 py-2 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent/40"
										style:border-color="hsl(var(--border))"
										data-testid="workbench-inspector-drawer-expand"
										title="Open inspector drawer"
									>
										<ChevronLeft class="h-3 w-3" aria-hidden="true" />
										<span style:writing-mode="vertical-rl">Inspector</span>
									</button>
								{/if}
							{:else}
								{#if !inspectorMaximized}
									{#if inspectorPosition === 'bottom'}
										<div
											role="separator"
											aria-orientation="horizontal"
											aria-label="Resize inspector drawer"
											onpointerdown={handleInspectorDrawerResizeStart}
											class="workbench-resize-handle flex h-3 w-full shrink-0 cursor-row-resize items-center justify-center rounded {inspectorDrawerResizing
												? 'workbench-resize-handle-active'
												: ''}"
											data-testid="workbench-inspector-drawer-resize"
											title="Drag to resize"
										>
											<div class="h-1 w-16 rounded-full"></div>
										</div>
									{:else}
										<div
											role="separator"
											aria-orientation="vertical"
											aria-label="Resize inspector drawer"
											onpointerdown={handleInspectorDrawerResizeStart}
											class="workbench-resize-handle flex w-3 shrink-0 cursor-col-resize flex-col items-center justify-center rounded {inspectorDrawerResizing
												? 'workbench-resize-handle-active'
												: ''}"
											data-testid="workbench-inspector-drawer-resize"
											title="Drag to resize"
										>
											<div class="h-16 w-1 rounded-full"></div>
										</div>
									{/if}
								{/if}
								{#if inspectorPosition === 'bottom'}
									<div
										bind:this={inspectorDrawerEl}
										class="flex min-h-0 w-full min-w-0 flex-col {inspectorMaximized
											? 'flex-1'
											: 'shrink-0'}"
										style:height={inspectorMaximized ? undefined : `${inspectorDrawerHeight}px`}
										data-testid="workbench-inspector-drawer"
									>
										{@render inspectorPanel()}
									</div>
								{:else}
									<div
										bind:this={inspectorDrawerEl}
										class="flex h-full min-h-0 min-w-0 flex-col {inspectorMaximized
											? 'flex-1'
											: 'shrink-0'}"
										style:width={inspectorMaximized ? undefined : `${inspectorDrawerWidth}px`}
										data-testid="workbench-inspector-drawer"
									>
										{@render inspectorPanel()}
									</div>
								{/if}
							{/if}
						</div>
					{:else if viewMode === 'cards'}
						<!-- Drop zone fallback / empty hint AND the cards live in
					     the same dnd container.  The container itself accepts
					     drops to create a new dimension; existing dim cards
					     intercept drops at their level.
					     When empty AND nothing is being dragged, the zone
					     shows a muted dashed boundary so the user can see
					     it's a target — otherwise the hint floats in white
					     space.  Once a dim card is in place we drop the
					     boundary so the cards aren't visually wrapped. -->
						<div
							role="region"
							aria-label="Dimensions drop zone"
							class="grid flex-1 grid-cols-1 gap-2 rounded-md border-2 border-dashed p-2 transition-colors lg:grid-cols-2 {dropRingFor(
								{ kind: 'dim-zone' }
							)} {store.dimensions.length === 0 && !isDragging ? 'border-border' : ''}"
							ondragover={(e) => handleDragOver(e, { kind: 'dim-zone' })}
							ondragleave={(e) => handleDragLeave(e, { kind: 'dim-zone' })}
							ondrop={handleDimensionsZoneDrop}
							data-testid="workbench-dimensions-drop"
						>
							{#if store.dimensions.length === 0}
								<div
									class="col-span-full flex flex-col items-center justify-center gap-2 p-8 text-center"
									style:color="hsl(var(--muted-foreground))"
								>
									<Layers class="h-6 w-6" aria-hidden="true" />
									<p class="text-sm font-medium" style:color="hsl(var(--foreground))">
										No dimensions yet
									</p>
									<p class="max-w-sm text-xs leading-relaxed">
										Drag a column, a table, or a join group from the left rail to create your first
										dimension — or start with an empty card and pull columns into it.
									</p>
									<Button
										size="sm"
										variant="outline"
										onclick={() => addDimensionAndFocus()}
										data-testid="workbench-empty-add-dimension"
										class="mt-1"
									>
										<Plus class="h-3 w-3" aria-hidden="true" />
										Add a dimension
									</Button>
								</div>
							{/if}

							{#each store.dimensions as dim (dim.id)}
								{@render dimensionCard(dim)}
							{/each}
						</div>
					{:else}
						<!-- Tree view: rendered via the shared `treeView` snippet
						     (also used by the Confirm cube > Tree sub-tab). -->
						{@render treeView()}
					{/if}
				</section>
			</div>
		</div>

		<!-- ── RIGHT RAIL: Measures.  Only relevant in Cards view, where
	     dimensions live as cards in the centre and measures need a
	     dedicated drop zone on the side.
	     Hidden in Columns mode — Measures is a dockable pane there.
	     Hidden in Tree mode — measures now live inside per-MG nodes
	     under each cube, so a global rail showing store.measures is
	     stale + redundant with the tree's own measure rendering. -->
		{#if viewMode === 'cards'}
			<aside
				class="flex w-72 shrink-0 flex-col gap-3 overflow-y-auto border-l p-3"
				style:background="hsl(var(--card))"
				style:border-color="hsl(var(--border))"
				aria-label="Measures"
				data-testid="workbench-measures-rail"
			>
				<section class="flex min-h-0 flex-1 flex-col gap-2" data-testid="workbench-measures-zone">
					<div class="flex items-center justify-between">
						<div class="flex items-center gap-1.5">
							<Sigma class="h-3.5 w-3.5 text-primary" aria-hidden="true" />
							<h3 class="text-xs font-semibold tracking-wide uppercase">Measures</h3>
						</div>
						<span class="text-[10px] text-muted-foreground">
							{measureCount}
							{measureCount === 1 ? 'measure' : 'measures'}
						</span>
					</div>
					{#if viewMode === 'cards'}
						<div
							role="region"
							aria-label="Measures drop zone"
							class="flex min-h-[220px] flex-1 flex-col gap-1.5 rounded-md border-2 border-dashed p-2 transition-colors {dropRingFor(
								{ kind: 'measure-zone' }
							)}"
							ondragover={(e) => handleDragOver(e, { kind: 'measure-zone' })}
							ondragleave={(e) => handleDragLeave(e, { kind: 'measure-zone' })}
							ondrop={handleMeasuresZoneDrop}
							data-testid="workbench-measures-drop"
						>
							{#if store.measures.length === 0}
								<div
									class="flex flex-1 flex-col items-center justify-center gap-1 p-4 text-center"
									style:color="hsl(var(--muted-foreground))"
								>
									<Sigma class="h-5 w-5" aria-hidden="true" />
									<p class="text-xs font-medium" style:color="hsl(var(--foreground))">
										Drag a numeric column here to add a measure.
									</p>
									<p class="text-[11px]">Defaults to <span class="font-mono">sum</span>.</p>
								</div>
							{:else}
								{#each store.measures as m (m.id)}
									{@render measureRow(m)}
								{/each}
							{/if}
						</div>
					{:else}
						<div
							role="region"
							aria-label="Measures tree"
							class="flex min-h-[220px] flex-1 flex-col gap-0.5 rounded-md border-2 border-dashed p-2 transition-colors {dropRingFor(
								{ kind: 'measure-zone' }
							)}"
							ondragover={(e) => handleDragOver(e, { kind: 'measure-zone' })}
							ondragleave={(e) => handleDragLeave(e, { kind: 'measure-zone' })}
							ondrop={handleMeasuresZoneDrop}
							data-testid="workbench-measures-tree"
						>
							{@render treeRootRow('Measures', 'measure')}
							{#if store.measures.length === 0}
								<p class="px-2 pl-6 text-[11px]" style:color="hsl(var(--muted-foreground))">
									Drag a numeric column onto this region to add one.
								</p>
							{:else}
								{#each store.measures as m (m.id)}
									{@render measureTreeRow(m)}
								{/each}
							{/if}
						</div>
					{/if}
				</section>
			</aside>
		{/if}
	{/if}
</div>

<!-- ── Cube switcher — inline popover at the top of Facts & Measures.
     Shows the active cube's name as a clickable trigger; menu lists every
     cube + a "+ New cube" affordance.  Renames + deletes live in the
     Inspector's Cubes tab; this stays a one-tap switcher.  Click-outside
     closes the menu via a window-level handler the trigger registers
     on open. ──────────────────────────────────────────────────────── -->
{#snippet cubeSwitcher()}
	{@const cube = selectedCube}
	{#if cube}
		<div class="relative min-w-0 flex-1">
			<button
				type="button"
				onclick={(e) => {
					e.stopPropagation();
					cubeMenuOpen = !cubeMenuOpen;
				}}
				class="inline-flex w-full items-center justify-between gap-1 truncate rounded border bg-background px-2 py-1 text-[11px] font-semibold tracking-wider uppercase transition-colors hover:bg-accent/40 focus-visible:border-ring focus-visible:ring-1 focus-visible:ring-ring focus-visible:outline-none"
				style:border-color="hsl(var(--border))"
				style:color="hsl(var(--foreground))"
				data-testid="workbench-cube-switcher-trigger"
				aria-haspopup="menu"
				aria-expanded={cubeMenuOpen}
				title="Switch cube"
			>
				<span class="min-w-0 flex-1 truncate text-left">{cube.name || 'Untitled cube'}</span>
				<ChevronDown class="h-3 w-3 shrink-0" aria-hidden="true" />
			</button>
			{#if cubeMenuOpen}
				<!-- Backdrop catches outside clicks. -->
				<button
					type="button"
					class="fixed inset-0 z-30 cursor-default"
					onclick={() => (cubeMenuOpen = false)}
					aria-label="Close cube menu"
					tabindex="-1"
				></button>
				<div
					role="menu"
					class="absolute top-full right-0 left-0 z-40 mt-1 flex flex-col gap-0.5 rounded-md border bg-popover p-1 shadow-md"
					style:border-color="hsl(var(--border))"
					data-testid="workbench-cube-switcher-menu"
				>
					{#each cubes as c (c.id)}
						{@const active = c.id === selectedCubeId}
						<button
							type="button"
							onclick={() => {
								switchCube(c.id);
								cubeMenuOpen = false;
							}}
							class="flex items-center justify-between gap-2 rounded px-2 py-1 text-left text-[11px] {active
								? 'bg-accent font-semibold text-accent-foreground'
								: 'hover:bg-accent/40'}"
							data-testid="workbench-cube-switcher-item"
							data-cube-id={c.id}
						>
							<span class="truncate">{c.name || 'Untitled cube'}</span>
							{#if active}
								<span class="shrink-0 font-mono text-[9px] opacity-70">●</span>
							{/if}
						</button>
					{/each}
					<div class="my-1 h-px shrink-0" style:background-color="hsl(var(--border))"></div>
					<button
						type="button"
						onclick={() => {
							addCube();
							cubeMenuOpen = false;
						}}
						class="flex items-center gap-2 rounded px-2 py-1 text-left text-[11px] text-primary hover:bg-accent/40"
						data-testid="workbench-cube-switcher-add"
					>
						<Plus class="h-3 w-3 shrink-0" aria-hidden="true" />
						New cube
					</button>
				</div>
			{/if}
		</div>
	{/if}
{/snippet}

<!-- ── Cube permissions fieldset ──────────────────────────────────
     Draft UI shared by (a) the collapsible Permissions section on
     an existing cube and (b) the inline new-cube form.  Two knobs
     mirror the schema-level ACL (`SaikuAclBackend`):
       - visibility: private / workspace / public
       - groups: comma-separated group names granted read+query
     Nothing is persisted here yet — the eventual backend is the
     cube ACL work tracked in #1029. -->
{#snippet permissionsFieldset(idPrefix: string)}
	<div class="flex flex-col gap-2 text-[11px]">
		<div class="flex flex-col gap-1">
			<span
				class="text-[10px] font-semibold tracking-wider uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				Visibility
			</span>
			<div
				class="inline-flex w-full overflow-hidden rounded border"
				style:border-color="hsl(var(--border))"
				role="radiogroup"
				aria-label="Cube visibility"
			>
				{#snippet visRadio(value: 'private' | 'workspace' | 'public', label: string, desc: string)}
					<button
						type="button"
						role="radio"
						aria-checked={cubeNewFormVisibility === value}
						onclick={() => (cubeNewFormVisibility = value)}
						class="inline-flex flex-1 flex-col items-start gap-0.5 border-l px-2 py-1 text-left transition-colors first:border-l-0 {cubeNewFormVisibility ===
						value
							? 'bg-primary text-primary-foreground'
							: 'hover:bg-accent/40'}"
						style:border-color="hsl(var(--border))"
						data-testid="{idPrefix}-visibility-{value}"
					>
						<span class="text-[10px] font-semibold uppercase">{label}</span>
						<span class="text-[9px] opacity-80">{desc}</span>
					</button>
				{/snippet}
				{@render visRadio('private', 'Private', 'Only you')}
				{@render visRadio('workspace', 'Workspace', 'Everyone in this workspace')}
				{@render visRadio('public', 'Public', 'Anyone with a query link')}
			</div>
		</div>
		<label class="flex flex-col gap-1">
			<span
				class="text-[10px] font-semibold tracking-wider uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				Groups with access
			</span>
			<input
				type="text"
				bind:value={cubeNewFormGroups}
				placeholder="finance-analysts, ops-leads"
				class="h-7 rounded border bg-background px-2 text-[11px] focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
				style:border-color="hsl(var(--border))"
				data-testid="{idPrefix}-groups"
			/>
			<span class="text-[10px] opacity-70">
				Comma-separated group names. Members of these groups get read + query.
			</span>
		</label>
	</div>
{/snippet}

<!-- ── Dimension card snippet ────────────────────────────────────── -->
{#snippet dimensionCard(dim: SchemaCanvasDimension)}
	<article
		class="flex flex-col rounded-md border bg-card p-2 shadow-sm transition-colors {dropRingFor({
			kind: 'dim',
			id: dim.id
		})}"
		style:border-color="hsl(var(--border))"
		ondragover={(e) => handleDragOver(e, { kind: 'dim', id: dim.id })}
		ondragleave={(e) => handleDragLeave(e, { kind: 'dim', id: dim.id })}
		ondrop={(e) => handleDimensionDrop(e, dim)}
		data-testid="workbench-dimension-card"
		data-dimension-id={dim.id}
	>
		<header class="flex items-center gap-2">
			<Layers class="h-3.5 w-3.5 shrink-0 text-primary" aria-hidden="true" />
			<input
				type="text"
				value={dim.name}
				oninput={(e) => renameDimension(dim.id, e.currentTarget.value)}
				placeholder="Dimension name"
				aria-label="Dimension name"
				class="h-6 min-w-0 flex-1 rounded border border-transparent bg-transparent px-1 text-xs font-semibold hover:border-input focus:border-ring focus:bg-background focus:ring-1 focus:ring-ring focus:outline-none"
				data-testid="workbench-dimension-name"
				use:autoFocus={focusDimId === dim.id}
			/>
			<button
				type="button"
				onclick={() => store.removeDimension(dim.id)}
				class="shrink-0 rounded p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
				aria-label="Delete dimension"
				title="Delete dimension"
				data-testid="workbench-dimension-delete"
			>
				<X class="h-3 w-3" aria-hidden="true" />
			</button>
		</header>

		<div class="mt-2 space-y-2">
			{#if dim.hierarchies.length === 1}
				<!-- Singleton hierarchy — render the levels list flat inside
				     the dim card.  No hierarchy name, no hier chrome, no
				     "All" checkbox: most dims have exactly one drill path
				     and the hierarchy is implicit in the dim itself.  The
				     "Split into hierarchies" link below reveals the
				     multi-hierarchy UI on demand. -->
				{@render levelListInline(dim, dim.hierarchies[0])}
				<button
					type="button"
					onclick={() => store.addHierarchy(dim.id)}
					class="flex w-full items-center justify-center gap-1 rounded py-1 text-[10px] text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
					data-testid="workbench-split-hierarchies"
					title="Add a second drilldown path (e.g. Calendar / Fiscal on a Time dim)"
				>
					<Plus class="h-3 w-3" aria-hidden="true" />
					Split into hierarchies
				</button>
			{:else}
				{#each dim.hierarchies as hier (hier.id)}
					{@render hierarchyBlock(dim, hier)}
				{/each}
				<button
					type="button"
					onclick={() => store.addHierarchy(dim.id)}
					class="flex w-full items-center justify-center gap-1 rounded border border-dashed py-1 text-[10px] text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
					style:border-color="hsl(var(--border))"
					data-testid="workbench-add-hierarchy"
				>
					<Plus class="h-3 w-3" aria-hidden="true" />
					Add hierarchy
				</button>
			{/if}
		</div>
	</article>
{/snippet}

<!-- ── Inline level list (singleton-hierarchy mode) ──────────────── -->
<!--
   When a dim has exactly one hierarchy we render its levels flat inside
   the dim card, with no hierarchy header.  Drops still land on the dim
   card itself (handleDimensionDrop → dim.hierarchies[0]), so the
   gesture is identical; the user just never sees the word "hierarchy".
   Same row markup as the multi-hierarchy hierarchyBlock so behaviour is
   consistent — re-use the level row, the empty-state hint, the drag
   semantics.
-->
{#snippet levelListInline(dim: SchemaCanvasDimension, hier: SchemaCanvasHierarchy)}
	<ol class="space-y-1" data-testid="workbench-level-list-inline" data-hierarchy-id={hier.id}>
		{#each hier.levels as lvl, idx (lvl.id)}
			<li
				class="flex items-center gap-1.5 rounded border bg-elev-2 px-1.5 py-1 text-[11px]"
				style:border-color="hsl(var(--border))"
				data-testid="workbench-level-row"
			>
				<span
					class="inline-flex h-4 w-4 shrink-0 items-center justify-center rounded bg-accent font-mono text-[9px] text-accent-foreground"
					aria-label="Level position"
				>
					{idx + 1}
				</span>
				<input
					type="text"
					value={lvl.name}
					oninput={(e) => renameLevel(dim.id, hier.id, lvl.id, e.currentTarget.value)}
					placeholder="Level"
					aria-label="Level name"
					class="h-5 min-w-0 flex-1 rounded border border-transparent bg-transparent px-1 hover:border-input focus:border-ring focus:bg-background focus:ring-1 focus:ring-ring focus:outline-none"
				/>
				<span
					class="shrink-0 truncate font-mono text-[9px] tracking-wide opacity-60"
					title="{tableNameFor(lvl.tableId)}.{lvl.columnName}"
				>
					{tableNameFor(lvl.tableId)}.{lvl.columnName}
				</span>
				<button
					type="button"
					onclick={() => store.removeLevel(dim.id, hier.id, lvl.id)}
					class="shrink-0 rounded p-0.5 text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
					aria-label="Remove level"
					title="Remove level"
				>
					<X class="h-2.5 w-2.5" aria-hidden="true" />
				</button>
			</li>
		{/each}
		{#if hier.levels.length === 0}
			<li
				class="rounded border border-dashed px-1.5 py-2 text-center text-[10px]"
				style:border-color="hsl(var(--border))"
				style:color="hsl(var(--muted-foreground))"
			>
				Drag a column here.
			</li>
		{/if}
	</ol>
{/snippet}

<!-- ── Hierarchy block snippet ──────────────────────────────────── -->
{#snippet hierarchyBlock(dim: SchemaCanvasDimension, hier: SchemaCanvasHierarchy)}
	<section
		class="rounded border p-1.5 transition-colors {dropRingFor({
			kind: 'hierarchy',
			dimensionId: dim.id,
			hierarchyId: hier.id
		})}"
		style:background="hsl(var(--background))"
		role="group"
		aria-label="Hierarchy {hier.name}"
		ondragover={(e) =>
			handleDragOver(e, { kind: 'hierarchy', dimensionId: dim.id, hierarchyId: hier.id })}
		ondragleave={(e) =>
			handleDragLeave(e, { kind: 'hierarchy', dimensionId: dim.id, hierarchyId: hier.id })}
		ondrop={(e) => handleHierarchyDrop(e, dim)}
		data-testid="workbench-hierarchy"
		data-hierarchy-id={hier.id}
	>
		<header class="flex items-center gap-1.5">
			<ChevronDown class="h-3 w-3 shrink-0 opacity-50" aria-hidden="true" />
			<input
				type="text"
				value={hier.name}
				oninput={(e) => renameHierarchy(dim.id, hier.id, e.currentTarget.value)}
				placeholder="Hierarchy"
				aria-label="Hierarchy name"
				class="h-5 min-w-0 flex-1 rounded border border-transparent bg-transparent px-1 text-[11px] font-medium hover:border-input focus:border-ring focus:bg-background focus:ring-1 focus:ring-ring focus:outline-none"
			/>
			<label
				class="inline-flex shrink-0 items-center gap-1 text-[10px] text-muted-foreground"
				title="Mondrian hasAll attribute"
			>
				<input
					type="checkbox"
					checked={hier.hasAll}
					onchange={(e) =>
						store.updateHierarchy(dim.id, hier.id, { hasAll: e.currentTarget.checked })}
					class="h-3 w-3"
				/>
				All
			</label>
			<button
				type="button"
				onclick={() => store.removeHierarchy(dim.id, hier.id)}
				class="shrink-0 rounded p-0.5 text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
				aria-label="Delete hierarchy"
				title="Delete hierarchy"
			>
				<X class="h-2.5 w-2.5" aria-hidden="true" />
			</button>
		</header>

		<ol class="mt-1.5 space-y-1">
			{#each hier.levels as lvl, idx (lvl.id)}
				<li
					class="flex items-center gap-1.5 rounded border bg-elev-2 px-1.5 py-1 text-[11px]"
					style:border-color="hsl(var(--border))"
					data-testid="workbench-level-row"
				>
					<span
						class="inline-flex h-4 w-4 shrink-0 items-center justify-center rounded bg-accent font-mono text-[9px] text-accent-foreground"
						aria-label="Level position"
					>
						{idx + 1}
					</span>
					<input
						type="text"
						value={lvl.name}
						oninput={(e) => renameLevel(dim.id, hier.id, lvl.id, e.currentTarget.value)}
						placeholder="Level"
						aria-label="Level name"
						class="h-5 min-w-0 flex-1 rounded border border-transparent bg-transparent px-1 hover:border-input focus:border-ring focus:bg-background focus:ring-1 focus:ring-ring focus:outline-none"
					/>
					<span
						class="shrink-0 truncate font-mono text-[9px] tracking-wide opacity-60"
						title="{tableNameFor(lvl.tableId)}.{lvl.columnName}"
					>
						{tableNameFor(lvl.tableId)}.{lvl.columnName}
					</span>
					<button
						type="button"
						onclick={() => store.removeLevel(dim.id, hier.id, lvl.id)}
						class="shrink-0 rounded p-0.5 text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
						aria-label="Remove level"
						title="Remove level"
					>
						<X class="h-2.5 w-2.5" aria-hidden="true" />
					</button>
				</li>
			{/each}
			{#if hier.levels.length === 0 && !dim.hierarchies.some((h) => h.levels.length > 0)}
				<!-- Show the empty-state nudge only when the WHOLE dim is
				     empty.  Once any other hierarchy in this dim has
				     content, the empty siblings are obvious from their
				     own missing rows — no need to re-pitch "drag a column
				     here" inside each one. -->
				<li
					class="rounded border border-dashed px-1.5 py-2 text-center text-[10px]"
					style:border-color="hsl(var(--border))"
					style:color="hsl(var(--muted-foreground))"
				>
					Drag a column here.
				</li>
			{/if}
		</ol>
	</section>
{/snippet}

<!-- ── Measure row snippet ──────────────────────────────────────── -->
{#snippet measureRow(m: SchemaCanvasMeasure)}
	<div
		role="button"
		tabindex="0"
		onclick={() => {
			selectedMeasureId = m.id;
			selectedAttributeKey = null;
			selectedLevelId = null;
			selectedHierarchyId = null;
			focus('measure', m.id);
		}}
		onkeydown={(e) => {
			if (e.key === 'Enter' || e.key === ' ') {
				e.preventDefault();
				selectedMeasureId = m.id;
				selectedAttributeKey = null;
				selectedLevelId = null;
				selectedHierarchyId = null;
				focus('measure', m.id);
			}
		}}
		class="flex cursor-pointer items-center gap-2 rounded border bg-elev-2 px-2 py-1.5 transition-colors {isFocused(
			'measure',
			m.id
		)
			? 'workbench-row-selected'
			: 'hover:bg-accent/30'}"
		style:border-color={isFocused('measure', m.id) ? '' : 'hsl(var(--border))'}
		data-testid="workbench-measure-row"
		data-measure-id={m.id}
	>
		<Hash class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
		<input
			type="text"
			value={m.name}
			oninput={(e) => renameMeasure(m.id, e.currentTarget.value)}
			placeholder="Measure name"
			aria-label="Measure name"
			class="h-6 min-w-0 flex-1 rounded border border-transparent bg-transparent px-1 text-xs font-medium hover:border-input focus:border-ring focus:bg-background focus:ring-1 focus:ring-ring focus:outline-none"
		/>
		<select
			value={m.aggregator}
			onchange={(e) =>
				store.updateMeasure(m.id, {
					aggregator: e.currentTarget.value as SchemaCanvasMeasure['aggregator']
				})}
			class="h-6 shrink-0 rounded border bg-background px-1 font-mono text-[10px]"
			style:border-color="hsl(var(--border))"
			aria-label="Aggregator"
		>
			{#each AGGREGATORS as agg (agg)}
				<option value={agg}>{agg}</option>
			{/each}
		</select>
		<span
			class="shrink-0 truncate font-mono text-[10px] opacity-70"
			title="{tableNameFor(m.tableId)}.{m.columnName}"
		>
			{tableNameFor(m.tableId)}.{m.columnName}
		</span>
		<button
			type="button"
			onclick={() => store.removeMeasure(m.id)}
			class="shrink-0 rounded p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
			aria-label="Delete measure"
			title="Delete measure"
			data-testid="workbench-measure-delete"
		>
			<X class="h-3 w-3" aria-hidden="true" />
		</button>
	</div>
{/snippet}

<!-- ── Tree-view snippets ───────────────────────────────────────── -->
<!--
   The tree renders the same dimensions / hierarchies / levels / measures
   data as the cards view, but as a Mondrian-style nested outline. Each
   row is hairline-dense (text-xs / [11px], py-0.5–py-1) with no card
   chrome. Drop semantics match the cards view: the Dimensions root +
   each Dimension card accept column drops as a new level (auto-creating
   a hierarchy if needed); each Hierarchy row accepts drops as a level;
   the Measures root accepts drops as a new measure. Faint right-aligned
   `[Type]` tags make the Mondrian model legible at a glance.
-->

{#snippet typeTag(label: string)}
	<span
		class="ml-auto shrink-0 rounded px-1.5 py-0.5 text-[9px] font-medium tracking-wide uppercase"
		style:color="hsl(var(--muted-foreground))"
		style:background="hsl(var(--accent))"
	>
		{label}
	</span>
{/snippet}

{#snippet treeRootRow(label: string, kind: 'dimension' | 'measure')}
	<div
		class="flex items-center gap-1.5 px-1 py-1 text-xs font-semibold"
		data-testid="workbench-tree-root"
		data-tree-root={kind}
	>
		<ChevronDown class="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />
		{#if kind === 'dimension'}
			<Layers class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
		{:else}
			<Sigma class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
		{/if}
		<span class="truncate">{label}</span>
	</div>
{/snippet}

{#snippet dimensionTreeRow(dim: SchemaCanvasDimension)}
	{@const isCollapsed = collapsedTreeDimensions.has(dim.id)}
	<div
		class="group flex flex-col rounded transition-colors {dropRingFor({
			kind: 'dim',
			id: dim.id
		})} border border-transparent"
		ondragover={(e) => handleDragOver(e, { kind: 'dim', id: dim.id })}
		ondragleave={(e) => handleDragLeave(e, { kind: 'dim', id: dim.id })}
		ondrop={(e) => handleDimensionDrop(e, dim)}
		data-testid="workbench-tree-dimension"
		data-dimension-id={dim.id}
	>
		<div class="flex items-center gap-1 pr-1 pl-4 text-xs hover:bg-accent/40">
			<button
				type="button"
				onclick={() => toggleTreeDimension(dim.id)}
				class="shrink-0 rounded p-0.5 text-muted-foreground hover:text-foreground"
				aria-label={isCollapsed ? 'Expand dimension' : 'Collapse dimension'}
			>
				{#if isCollapsed}
					<ChevronRight class="h-3 w-3" aria-hidden="true" />
				{:else}
					<ChevronDown class="h-3 w-3" aria-hidden="true" />
				{/if}
			</button>
			<Layers class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
			<input
				type="text"
				value={dim.name}
				oninput={(e) => renameDimension(dim.id, e.currentTarget.value)}
				placeholder="Dimension name"
				aria-label="Dimension name"
				class="h-5 min-w-0 flex-1 rounded border border-transparent bg-transparent px-1 text-xs font-medium hover:border-input focus:border-ring focus:bg-background focus:ring-1 focus:ring-ring focus:outline-none"
				data-testid="workbench-tree-dimension-name"
				use:autoFocus={focusDimId === dim.id}
			/>
			{@render typeTag('Dimension')}
			<button
				type="button"
				onclick={() => store.removeDimension(dim.id)}
				class="ml-1 shrink-0 rounded p-0.5 text-muted-foreground opacity-0 transition-colors group-hover:opacity-100 hover:bg-accent hover:text-accent-foreground"
				aria-label="Delete dimension"
				title="Delete dimension"
			>
				<X class="h-2.5 w-2.5" aria-hidden="true" />
			</button>
		</div>

		{#if !isCollapsed}
			<div class="flex flex-col">
				{#if dim.hierarchies.length === 1}
					<!-- Singleton hierarchy in the tree view too — skip the
					     hierarchy row entirely and render its levels indented
					     directly under the dim row.  Reveal the multi-hierarchy
					     UI via "Split into hierarchies" below. -->
					{@const hier = dim.hierarchies[0]}
					{#if hier.levels.length === 0}
						<p class="py-0.5 pr-2 pl-10 text-[10px]" style:color="hsl(var(--muted-foreground))">
							Drag a column onto this dimension.
						</p>
					{:else}
						{#each hier.levels as lvl (lvl.id)}
							{@render levelTreeRow(dim, hier, lvl, 'pl-10')}
						{/each}
					{/if}
					<button
						type="button"
						onclick={() => store.addHierarchy(dim.id)}
						class="flex items-center gap-1 rounded px-1 py-0.5 pl-10 text-left text-[11px] text-muted-foreground hover:bg-accent/40"
						data-testid="workbench-tree-split-hierarchies"
						title="Add a second drilldown path (e.g. Calendar / Fiscal on a Time dim)"
					>
						<Plus class="h-3 w-3" aria-hidden="true" />
						Split into hierarchies
					</button>
				{:else}
					{#each dim.hierarchies as hier (hier.id)}
						{@render hierarchyTreeRow(dim, hier)}
					{/each}
					<button
						type="button"
						onclick={() => store.addHierarchy(dim.id)}
						class="flex items-center gap-1 rounded px-1 py-0.5 pl-10 text-left text-[11px] text-muted-foreground hover:bg-accent/40"
						data-testid="workbench-tree-add-hierarchy"
					>
						<Plus class="h-3 w-3" aria-hidden="true" />
						Add hierarchy
					</button>
				{/if}
				<!-- Attributes section — every attribute on the dim, showing
				     the display-name caption (from the schema) as the
				     primary label with the raw SQL column name below.  A
				     key icon on the LEFT swaps in when this row is the
				     dim's primary key; a hover-to-set key toggle on the
				     RIGHT sits in its own chip separated from the type
				     tag.  Mondrian 4 needs exactly one key attribute per
				     dim (marks the PK column on the dim table); marking
				     one unmarks any other. -->
				{#if (dim.attributes ?? []).length > 0}
					<div
						class="mt-1 flex items-center gap-1 pl-10 text-[10px] font-semibold tracking-wider uppercase"
						style:color="hsl(var(--muted-foreground))"
					>
						Attributes
					</div>
					{#if !dimKeyIdentity(dim)}
						<!-- Empty-state hint — only when no key selected.
						     Sits below the Attributes header; muted so it
						     doesn't shout at the user, small icon so it
						     reads as guidance not an error. -->
						<div
							class="mt-0.5 flex items-center gap-1.5 pl-10 text-[10px] leading-snug text-muted-foreground"
							data-testid="workbench-tree-attribute-empty-key"
						>
							<KeyRound class="h-3 w-3 shrink-0" aria-hidden="true" />
							<span>
								No key selected — click the
								<KeyRound class="inline h-2.5 w-2.5" aria-hidden="true" />
								icon on an attribute, or set it in the Inspector.
							</span>
						</div>
					{/if}
					{@const treeResolvedKey = resolveKeyAttribute(dim)}
					{#each dim.attributes ?? [] as attr, __ai (`${attr.tableId}::${attr.columnName}::${attr.name ?? __ai}`)}
						{@const dimSrcId = dim.sourceTableId ?? dim.primaryKeyTableId}
						{@const attrIdent = attr.name ?? attr.columnName}
						<!-- Compare against the SINGLE resolved key attribute — logical
						     match first, column-fallback only when unambiguous. -->
						{@const isKey =
							!!treeResolvedKey &&
							treeResolvedKey.attr.tableId === attr.tableId &&
							treeResolvedKey.attr.columnName === attr.columnName &&
							(treeResolvedKey.attr.name ?? treeResolvedKey.attr.columnName) === attrIdent}
						{@const displayName = attr.name ?? attr.columnName}
						{@const hasCaption = attr.name && attr.name !== attr.columnName}
						<div
							class="group flex items-start gap-2 rounded pr-1 pl-10 text-[11px] hover:bg-accent/40 {isKey
								? 'bg-primary/5'
								: ''}"
							data-testid="workbench-tree-attribute"
							data-column={attr.columnName}
						>
							<span
								class="mt-0.5 flex h-4 w-3 shrink-0 items-center justify-center"
								aria-hidden="true"
							>
								{#if isKey}
									<KeyRound class="h-3 w-3 text-primary" aria-hidden="true" />
								{:else}
									<span class="text-muted-foreground">•</span>
								{/if}
							</span>
							<span class="flex min-w-0 flex-1 flex-col leading-tight">
								<!-- Primary line: caption from the schema when
								     present, otherwise the raw column name. -->
								<span class="min-w-0 truncate text-foreground">{displayName}</span>
								<!-- Secondary line: the raw SQL column name in
								     mono, muted, only when a caption exists AND
								     differs from the column. -->
								{#if hasCaption}
									<span class="min-w-0 truncate font-mono text-[10px] text-muted-foreground">
										{attr.columnName}
									</span>
								{/if}
							</span>
							<button
								type="button"
								onclick={() => {
									if (isKey) {
										dim.primaryKey = undefined;
										dim.primaryKeyTableId = undefined;
									} else {
										// primaryKey holds the ATTRIBUTE's identity (logical
										// name first, columnName as fallback), not just the
										// column — two attributes on the same column need to
										// disambiguate.
										dim.primaryKey = attrIdent;
										dim.primaryKeyTableId = attr.tableId !== dimSrcId ? attr.tableId : undefined;
									}
								}}
								class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-md border transition-colors {isKey
									? 'border-primary bg-primary/10 text-primary'
									: 'border-border text-muted-foreground opacity-0 group-hover:opacity-100 hover:border-primary/60 hover:text-primary'}"
								aria-pressed={isKey}
								aria-label={isKey ? 'Unmark as key' : 'Set as key'}
								title={isKey ? 'Unmark as key' : 'Set as key'}
								data-testid="workbench-tree-attribute-key"
							>
								<KeyRound class="h-3 w-3" aria-hidden="true" />
							</button>
							<!-- Type tag pushed to its own space so it reads
							     as a distinct chip, not conflated with the
							     PK toggle. -->
							<span class="ml-1 shrink-0">
								{@render typeTag('Attribute')}
							</span>
						</div>
					{/each}
				{/if}
			</div>
		{/if}
	</div>
{/snippet}

{#snippet hierarchyTreeRow(dim: SchemaCanvasDimension, hier: SchemaCanvasHierarchy)}
	{@const isCollapsed = collapsedTreeHierarchies.has(hier.id)}
	<div
		class="group flex flex-col rounded transition-colors {dropRingFor({
			kind: 'hierarchy',
			dimensionId: dim.id,
			hierarchyId: hier.id
		})} border border-transparent"
		ondragover={(e) =>
			handleDragOver(e, { kind: 'hierarchy', dimensionId: dim.id, hierarchyId: hier.id })}
		ondragleave={(e) =>
			handleDragLeave(e, { kind: 'hierarchy', dimensionId: dim.id, hierarchyId: hier.id })}
		ondrop={(e) => handleHierarchyDrop(e, dim)}
		data-testid="workbench-tree-hierarchy"
		data-hierarchy-id={hier.id}
	>
		<div class="flex items-center gap-1 pr-1 pl-8 text-[11px] hover:bg-accent/40">
			<button
				type="button"
				onclick={() => toggleTreeHierarchy(hier.id)}
				class="shrink-0 rounded p-0.5 text-muted-foreground hover:text-foreground"
				aria-label={isCollapsed ? 'Expand hierarchy' : 'Collapse hierarchy'}
			>
				{#if isCollapsed}
					<ChevronRight class="h-3 w-3" aria-hidden="true" />
				{:else}
					<ChevronDown class="h-3 w-3" aria-hidden="true" />
				{/if}
			</button>
			<input
				type="text"
				value={hier.name}
				oninput={(e) => renameHierarchy(dim.id, hier.id, e.currentTarget.value)}
				placeholder="Hierarchy"
				aria-label="Hierarchy name"
				class="h-5 min-w-0 flex-1 rounded border border-transparent bg-transparent px-1 text-[11px] hover:border-input focus:border-ring focus:bg-background focus:ring-1 focus:ring-ring focus:outline-none"
			/>
			{@render typeTag('Hierarchy')}
			<button
				type="button"
				onclick={() => store.removeHierarchy(dim.id, hier.id)}
				class="ml-1 shrink-0 rounded p-0.5 text-muted-foreground opacity-0 transition-colors group-hover:opacity-100 hover:bg-accent hover:text-accent-foreground"
				aria-label="Delete hierarchy"
				title="Delete hierarchy"
			>
				<X class="h-2.5 w-2.5" aria-hidden="true" />
			</button>
		</div>

		{#if !isCollapsed}
			{#if hier.levels.length === 0}
				<p class="py-0.5 pr-2 pl-14 text-[10px]" style:color="hsl(var(--muted-foreground))">
					Drag a column or pick an attribute below to add a level.
				</p>
			{:else}
				{#each hier.levels as lvl (lvl.id)}
					{@render levelTreeRow(dim, hier, lvl)}
				{/each}
			{/if}
			<!-- Manual "Add level" picker — drag/drop is the headline UX, but
			     a select-based picker makes the affordance explicit in the
			     tree view (mirrors the columns view's Attributes pane). -->
			{@const usedCols = new Set(hier.levels.map((l) => l.columnName))}
			{@const availableAttrs = (dim.attributes ?? []).filter((a) => !usedCols.has(a.columnName))}
			{#if availableAttrs.length > 0}
				<div class="flex items-center gap-1 py-0.5 pl-14">
					<Plus class="h-3 w-3 shrink-0 text-muted-foreground" aria-hidden="true" />
					<select
						value=""
						onchange={(e) => {
							const target = e.currentTarget;
							const ident = target.value;
							if (!ident) return;
							// value is the attribute IDENTITY (logical name if set,
							// columnName otherwise) — disambiguates two attributes
							// on the same column.
							const attr = (dim.attributes ?? []).find((a) => (a.name ?? a.columnName) === ident);
							if (attr) {
								store.addLevel(dim.id, hier.id, {
									tableId: attr.tableId,
									columnName: attr.columnName
								});
							}
							target.value = '';
						}}
						class="h-5 max-w-[14rem] rounded border bg-background px-1 text-[11px] focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
						style:border-color="hsl(var(--border))"
						data-testid="workbench-tree-add-level"
					>
						<option value="">Add level…</option>
						{#each availableAttrs as a, __lai (`${a.tableId}::${a.columnName}::${a.name ?? __lai}`)}
							{@const ident = a.name ?? a.columnName}
							<option value={ident}>{ident}</option>
						{/each}
					</select>
				</div>
			{:else if (dim.attributes ?? []).length === 0}
				<p class="py-0.5 pr-2 pl-14 text-[10px]" style:color="hsl(var(--muted-foreground))">
					Pick attributes on the dimension first (Columns view → Attributes pane).
				</p>
			{/if}
		{/if}
	</div>
{/snippet}

{#snippet levelTreeRow(
	dim: SchemaCanvasDimension,
	hier: SchemaCanvasHierarchy,
	lvl: SchemaCanvasLevel,
	indentClass: string = 'pl-14'
)}
	<!-- Level rename intentionally disabled in tree view (saiku-cloud
	     followup).  A Mondrian Level is a bound REFERENCE to an attribute
	     column; the displayed label is the column itself.  Renaming would
	     decouple display from the binding and is a separate concern that
	     hasn't been spec'd yet. -->
	<div
		class="group flex items-center gap-1 rounded pr-1 text-[11px] hover:bg-accent/40 {indentClass}"
		data-testid="workbench-tree-level"
		data-level-id={lvl.id}
	>
		<span class="shrink-0 text-muted-foreground" aria-hidden="true">•</span>
		<span class="min-w-0 flex-1 truncate">{lvl.columnName}</span>
		<span
			class="shrink-0 truncate font-mono text-[9px] opacity-60"
			title="{tableNameFor(lvl.tableId)}.{lvl.columnName}"
		>
			{tableNameFor(lvl.tableId)}.{lvl.columnName}
		</span>
		{@render typeTag('Level')}
		<button
			type="button"
			onclick={() => store.removeLevel(dim.id, hier.id, lvl.id)}
			class="ml-1 shrink-0 rounded p-0.5 text-muted-foreground opacity-0 transition-colors group-hover:opacity-100 hover:bg-accent hover:text-accent-foreground"
			aria-label="Remove level"
			title="Remove level"
		>
			<X class="h-2.5 w-2.5" aria-hidden="true" />
		</button>
	</div>
{/snippet}

{#snippet measureTreeRow(m: SchemaCanvasMeasure)}
	<div
		class="group flex items-center gap-1 rounded pr-1 pl-6 text-[11px] hover:bg-accent/40"
		data-testid="workbench-tree-measure"
		data-measure-id={m.id}
	>
		<span class="shrink-0 text-muted-foreground" aria-hidden="true">•</span>
		<Hash class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
		<input
			type="text"
			value={m.name}
			oninput={(e) => renameMeasure(m.id, e.currentTarget.value)}
			placeholder="Measure name"
			aria-label="Measure name"
			class="h-5 min-w-0 flex-1 rounded border border-transparent bg-transparent px-1 hover:border-input focus:border-ring focus:bg-background focus:ring-1 focus:ring-ring focus:outline-none"
		/>
		<select
			value={m.aggregator}
			onchange={(e) =>
				store.updateMeasure(m.id, {
					aggregator: e.currentTarget.value as SchemaCanvasMeasure['aggregator']
				})}
			class="h-5 shrink-0 rounded border bg-background px-1 font-mono text-[9px]"
			style:border-color="hsl(var(--border))"
			aria-label="Aggregator"
		>
			{#each AGGREGATORS as agg (agg)}
				<option value={agg}>{agg}</option>
			{/each}
		</select>
		<span
			class="shrink-0 truncate font-mono text-[9px] opacity-60"
			title="{tableNameFor(m.tableId)}.{m.columnName}"
		>
			{tableNameFor(m.tableId)}.{m.columnName}
		</span>
		{@render typeTag('Measure')}
		<button
			type="button"
			onclick={() => store.removeMeasure(m.id)}
			class="ml-1 shrink-0 rounded p-0.5 text-muted-foreground opacity-0 transition-colors group-hover:opacity-100 hover:bg-accent hover:text-accent-foreground"
			aria-label="Delete measure"
			title="Delete measure"
		>
			<X class="h-2.5 w-2.5" aria-hidden="true" />
		</button>
	</div>
{/snippet}

<!-- ── Measure group tree row ────────────────────────────────────────
     Child of a cube node.  Shows fact-table + measures + dimension
     links as plain-text children for at-a-glance scanning; the actual
     editing UI is the existing `paneMeasureGroupContent` snippet,
     surfaced via the Inspector Properties tab when the MG row is
     selected.  This keeps the canonical editor in one place. -->
{#snippet measureGroupTreeRow(cube: WorkbenchCube, mg: WorkbenchMeasureGroup)}
	{@const isCollapsed = collapsedTreeMeasureGroups.has(mg.id)}
	{@const isSelected = isFocused('mg', mg.id)}
	{@const factTable = mg.factTableId
		? (store.doc.tables.find((t) => t.id === mg.factTableId) ?? null)
		: null}
	{@const dimLinks = mg.dimensionLinks ?? []}
	<div
		class="group flex flex-col rounded border border-transparent"
		data-testid="workbench-tree-mg"
		data-mg-id={mg.id}
	>
		<div
			role="button"
			tabindex="0"
			onclick={() => selectTreeMeasureGroup(cube.id, mg.id)}
			onkeydown={(e) => {
				if (e.key === 'Enter' || e.key === ' ') {
					e.preventDefault();
					selectTreeMeasureGroup(cube.id, mg.id);
				}
			}}
			class="flex cursor-pointer items-center gap-1 pr-1 pl-6 text-[11px] hover:bg-accent/40 {isSelected
				? 'workbench-row-selected'
				: ''}"
		>
			<button
				type="button"
				onclick={(e) => {
					e.stopPropagation();
					toggleTreeMeasureGroup(mg.id);
				}}
				class="shrink-0 rounded p-0.5 text-muted-foreground hover:text-foreground"
				aria-label={isCollapsed ? 'Expand measure group' : 'Collapse measure group'}
			>
				{#if isCollapsed}
					<ChevronRight class="h-3 w-3" aria-hidden="true" />
				{:else}
					<ChevronDown class="h-3 w-3" aria-hidden="true" />
				{/if}
			</button>
			<Sigma class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
			{#if factsEditingMeasureGroupId === mg.id && cube.id === selectedCubeId}
				<input
					type="text"
					value={mg.name}
					oninput={(e) => renameFactsMeasureGroup(mg.id, e.currentTarget.value)}
					onclick={(e) => e.stopPropagation()}
					onkeydown={(e) => {
						e.stopPropagation();
						if (e.key === 'Enter' || e.key === 'Escape') {
							(e.currentTarget as HTMLInputElement).blur();
						}
					}}
					onblur={() => (factsEditingMeasureGroupId = null)}
					placeholder="Measure group"
					aria-label="Measure group name"
					class="h-5 min-w-0 flex-1 rounded border bg-background px-1 font-medium focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
					style:border-color="hsl(var(--border))"
					use:autoFocus={factsEditingMeasureGroupId === mg.id}
				/>
			{:else}
				<span class="min-w-0 flex-1 truncate font-medium" title="Click to edit; pencil to rename">
					{mg.name || 'Untitled group'}
				</span>
			{/if}
			{@render typeTag('Measure group')}
			<button
				type="button"
				onclick={(e) => {
					e.stopPropagation();
					if (cube.id !== selectedCubeId) switchCube(cube.id);
					factsEditingMeasureGroupId = mg.id;
				}}
				class="ml-1 shrink-0 rounded p-0.5 text-muted-foreground opacity-0 transition-colors group-hover:opacity-100 hover:bg-accent hover:text-accent-foreground"
				aria-label="Rename measure group"
				title="Rename measure group"
				data-testid="workbench-tree-mg-rename"
			>
				<Pencil class="h-2.5 w-2.5" aria-hidden="true" />
			</button>
			<button
				type="button"
				onclick={(e) => {
					e.stopPropagation();
					if (cube.id !== selectedCubeId) switchCube(cube.id);
					removeFactsMeasureGroup(mg.id);
					if (selectedTreeMeasureGroupId === mg.id) selectedTreeMeasureGroupId = null;
				}}
				class="shrink-0 rounded p-0.5 text-muted-foreground opacity-0 transition-colors group-hover:opacity-100 hover:bg-accent hover:text-accent-foreground"
				aria-label="Delete measure group"
				title="Delete measure group"
				data-testid="workbench-tree-mg-delete"
			>
				<X class="h-2.5 w-2.5" aria-hidden="true" />
			</button>
		</div>
		{#if !isCollapsed}
			<div class="flex flex-col">
				<!-- Fact table child node.  In edit surfaces (D&H tree view)
				     the user can change the binding inline via a <select>.
				     On the Confirm cube > Tree sub-tab (store.mode ===
				     'validate') this row is read-only — Confirm is a
				     visualization, not another edit surface. -->
				<div
					class="flex items-center gap-1 rounded pr-1 pl-12 text-[11px] text-muted-foreground"
					data-testid="workbench-tree-mg-fact"
				>
					<Database class="h-3 w-3 shrink-0" aria-hidden="true" />
					{#if store.mode === 'validate'}
						<!-- Read-only in Confirm cube. -->
						{#if factTable}
							<span class="min-w-0 flex-1 truncate font-mono" title="Fact table: {factTable.name}">
								{factTable.name}
							</span>
						{:else}
							<span class="min-w-0 flex-1 truncate text-warning italic"> Fact table not set </span>
						{/if}
					{:else if store.sourceTables.length === 0}
						<span class="min-w-0 flex-1 truncate italic">
							Source catalog is empty — pick a Source on the top bar.
						</span>
					{:else}
						<select
							value={mg.factTableId
								? (() => {
										const onCanvas = store.doc.tables.find((t) => t.id === mg.factTableId);
										if (!onCanvas) return '';
										return `${onCanvas.schema ?? ''}::${onCanvas.name}`;
									})()
								: ''}
							onchange={(e) => {
								const target = e.currentTarget;
								const key = target.value;
								if (!key) return;
								if (cube.id !== selectedCubeId) switchCube(cube.id);
								const liveMG = factsMeasureGroups.find((x) => x.id === mg.id);
								if (!liveMG) return;
								const [schema, name] = key.split('::');
								const cand = store.sourceTables.find(
									(t) => t.name === name && (t.schema ?? '') === schema
								);
								if (cand) bindMGToSourceTable(liveMG, cand);
							}}
							class="h-5 min-w-0 flex-1 rounded border bg-background px-1 text-[11px] focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none {mg.factTableId
								? 'font-mono'
								: 'italic'}"
							style:border-color="hsl(var(--border))"
							data-testid="workbench-tree-mg-fact-picker"
							title={factTable ? `Fact table: ${factTable.name}` : 'Pick a fact table'}
						>
							<option value="" disabled={!!mg.factTableId}>Pick a fact table…</option>
							{#each store.sourceTables as t (`${t.schema ?? ''}::${t.name}`)}
								<option value="{t.schema ?? ''}::{t.name}">{t.name}</option>
							{/each}
						</select>
					{/if}
					{@render typeTag('Fact')}
				</div>
				<!-- Measures child nodes — one row per measureColumns entry. -->
				{#if mg.measureColumns.length === 0}
					<p class="py-0.5 pr-2 pl-12 text-[10px]" style:color="hsl(var(--muted-foreground))">
						No measures picked yet.
					</p>
				{:else}
					{#each mg.measureColumns as col (col)}
						<div
							class="flex items-center gap-1 rounded pr-1 pl-12 text-[11px] hover:bg-accent/40"
							data-testid="workbench-tree-mg-measure"
							data-column={col}
						>
							<Hash class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
							<span class="min-w-0 flex-1 truncate font-mono">{col}</span>
							{@render typeTag('Measure')}
						</div>
					{/each}
				{/if}
				<!-- Dimension links — resolved to dim name via store.dimensions. -->
				{#if dimLinks.length === 0}
					<p class="py-0.5 pr-2 pl-12 text-[10px]" style:color="hsl(var(--muted-foreground))">
						No shared dimensions linked yet.
					</p>
				{:else}
					{#each dimLinks as link (link.dimensionId)}
						{@const dim = store.dimensions.find((d) => d.id === link.dimensionId)}
						<div
							class="flex items-center gap-1 rounded pr-1 pl-12 text-[11px] hover:bg-accent/40"
							data-testid="workbench-tree-mg-dimlink"
							data-dim-id={link.dimensionId}
						>
							<Layers class="h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
							<span class="min-w-0 flex-1 truncate font-medium">
								{dim?.name ?? '(missing dimension)'}
							</span>
							{#if link.foreignKeyColumn}
								<span
									class="shrink-0 truncate font-mono text-[9px] opacity-60"
									title={link.foreignKeyColumn}
								>
									fk: {link.foreignKeyColumn}
								</span>
							{/if}
							{@render typeTag('Link')}
						</div>
					{/each}
				{/if}
			</div>
		{/if}
	</div>
{/snippet}

<!-- ── Columns view: 3 swappable Miller-style panes ───────────────── -->
<!--
   Each pane has a header strip with a kind-picker and a Pin toggle.
   Pinned panes can't be repurposed (prevents the user from accidentally
   blowing away the Dimensions pane while clicking around). Default
   slots are pre-pinned for a first-timer so the layout is stable until
   they explicitly unpin one.

   Selection model: clicking a dim/hierarchy/measure in any pane sets
   the shared selected* state, which other panes read.  E.g. clicking
   "Customer" in a Dimensions pane updates the Levels pane (which shows
   the implicit-default hier's levels by default) and the Properties
   pane.
-->

<!-- ── Confirm cube > Tree sub-tab.  Read-only outline of the whole
     schema.  Every cube is a root: its fact table, measure groups,
     dim links stack underneath.  Shared dimensions live in their own
     bucket at the bottom (schema-scope, not tied to any one cube).
     Purely informational — no drag, no edit; edit surfaces stay on
     the Dim & Hier and Facts & Measures tabs. -->

<!-- ── Deprecated tree view, re-exposed as a snippet so the
     Confirm cube > Tree sub-tab can render the same DOM the
     D&H tab used to render when viewMode === 'tree'.  All
     state coupling (treeWorkbenchBodyEl, treeInspectorFraction,
     treeFocus, etc.) is preserved. -->
{#snippet treeView()}
	<div bind:this={treeWorkbenchBodyEl} class="flex h-full min-h-0 flex-1 flex-row gap-2">
		<!-- LEFT: tree (existing dim-zone div) -->
		<div
			role="region"
			aria-label="Schema tree"
			class="flex min-h-0 flex-col gap-0.5 overflow-y-auto rounded-md border-2 border-dashed p-2 transition-colors {dropRingFor(
				{ kind: 'dim-zone' }
			)} {store.dimensions.length === 0 && !isDragging ? 'border-border' : ''}"
			style:flex={treeInspectorCollapsed ? '1 1 0' : `${1 - treeInspectorFraction} 1 0`}
			ondragover={(e) => handleDragOver(e, { kind: 'dim-zone' })}
			ondragleave={(e) => handleDragLeave(e, { kind: 'dim-zone' })}
			ondrop={handleDimensionsZoneDrop}
			data-testid="workbench-dimensions-tree"
		>
			<!-- Cube switcher header.  Mirrors the Columns view's
							     Facts & Measures section header so the user has
							     ONE affordance ("which cube am I in?") whichever
							     view they're using.  Reuses the cubeSwitcher
							     snippet — single source of truth. -->
			<div
				class="flex shrink-0 items-center gap-2 border-b pb-1.5"
				style:border-color="hsl(var(--border))"
				data-testid="workbench-tree-cube-header"
			>
				<!-- Cube switcher only relevant when the tree is focused
								     on cube facts/measures.  In dims-focus mode shared
								     dimensions belong to the schema (not any one cube),
								     so the switcher would be misleading. -->
				{#if treeFocus === 'cubes'}
					<span
						class="shrink-0 text-[11px] font-semibold tracking-wider uppercase"
						style:color="hsl(var(--muted-foreground))"
					>
						Cube
					</span>
					{#if store.mode === 'validate'}
						<!-- Confirm cube > Tree: the cubes rail on the left
						     already handles cube selection, so we skip the
						     dropdown and just show the current cube name
						     as context. -->
						<span
							class="min-w-0 flex-1 truncate text-[11px] font-semibold text-foreground"
							title={selectedCube?.name || 'Untitled cube'}
						>
							{selectedCube?.name || 'Untitled cube'}
						</span>
					{:else}
						{@render cubeSwitcher()}
					{/if}
				{:else}
					<span
						class="flex-1 text-[11px] font-semibold tracking-wider uppercase"
						style:color="hsl(var(--muted-foreground))"
					>
						Shared across the schema
					</span>
				{/if}
				<!-- Single stacked-chevron toggle replaces the prior
								     expand-/collapse-all pair.  "Anything collapsed?
								     → expand it all; everything expanded → collapse
								     it all."  Touches every collapse Set in the
								     tree plus the Shared Dimensions branch flag. -->
				<button
					type="button"
					onclick={() => (anyTreeCollapsed ? expandAllTree() : collapseAllTree())}
					class="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
					aria-label={anyTreeCollapsed ? 'Expand all' : 'Collapse all'}
					title={anyTreeCollapsed ? 'Expand all' : 'Collapse all'}
					data-testid="workbench-tree-expand-collapse-toggle"
				>
					<ChevronsUpDown class="h-3 w-3" aria-hidden="true" />
				</button>
			</div>
			<!-- ── Measure groups of the selected cube ─────────
							     The cube switcher in the header already names the
							     active cube, so a "Cubes" root + per-cube nodes
							     would duplicate that affordance.  Show the MGs of
							     the selected cube directly. -->
			{#if treeFocus === 'cubes' && selectedCube}
				{@const cubeMGs =
					selectedCube.id === selectedCubeId ? factsMeasureGroups : selectedCube.measureGroups}
				{#each cubeMGs as mg (mg.id)}
					{@render measureGroupTreeRow(selectedCube, mg)}
				{/each}
				{#if store.mode !== 'validate'}
					<button
						type="button"
						onclick={() => {
							if (selectedCube.id !== selectedCubeId) switchCube(selectedCube.id);
							addFactsMeasureGroup();
							selectedTreeMeasureGroupId = factsSelectedGroupId;
							selectedTreeCubeId = selectedCube.id;
						}}
						class="flex items-center gap-1 rounded px-2 py-0.5 pl-2 text-left text-[11px] text-muted-foreground hover:bg-accent/40"
						data-testid="workbench-tree-mg-add"
					>
						<Plus class="h-3 w-3" aria-hidden="true" />
						New measure group
					</button>
				{/if}
			{/if}
			<!-- ── Shared Dimensions branch ─────────────────── -->
			{#if treeFocus === 'dims'}
				{#if store.dimensions.length === 0}
					<div
						class="flex flex-col items-center justify-center gap-2 p-8 text-center"
						style:color="hsl(var(--muted-foreground))"
					>
						<p class="text-sm font-medium" style:color="hsl(var(--foreground))">
							No dimensions yet
						</p>
						<p class="max-w-sm text-xs leading-relaxed">
							Drag a column, a table, or a join group from the left rail — or hit
							<span class="font-medium">Add dimension</span> at the top.
						</p>
					</div>
				{/if}
				{#each store.dimensions as dim (dim.id)}
					{@render dimensionTreeRow(dim)}
				{/each}
				{#if store.mode !== 'validate'}
					<button
						type="button"
						onclick={() => addDimensionAndFocus()}
						class="flex items-center gap-1 rounded px-2 py-0.5 pl-6 text-left text-[11px] text-muted-foreground hover:bg-accent/40"
						data-testid="workbench-tree-add-dimension"
					>
						<Plus class="h-3 w-3" aria-hidden="true" />
						Add dimension
					</button>
				{/if}
			{/if}
		</div>

		<!-- HANDLE: vertical resize bar.  Pointerdown
						     uncollapses if needed, then starts the drag —
						     same UX as the Columns bottom-inspector handle. -->
		<div
			role="separator"
			aria-orientation="vertical"
			aria-label="Resize tree inspector"
			onpointerdown={(e) => {
				if (treeInspectorCollapsed) treeInspectorCollapsed = false;
				handleTreeInspectorResizeStart(e);
			}}
			class="workbench-resize-handle workbench-resize-handle-vertical flex w-3 shrink-0 cursor-col-resize items-center justify-center rounded {treeInspectorResizing
				? 'workbench-resize-handle-active'
				: ''}"
			data-testid="workbench-tree-inspector-resize"
			title="Drag to resize · click to restore split"
		>
			<div class="h-16 w-1 rounded-full"></div>
		</div>

		<!-- RIGHT: inspector.  Visually identical to the
						     Columns bottom inspector (header + tab strip +
						     body) — only the collapse chevron points right
						     (collapse-to-right) and the collapsed strip is a
						     vertical sliver. -->
		{#if treeInspectorCollapsed}
			<button
				type="button"
				onclick={() => (treeInspectorCollapsed = false)}
				class="flex w-8 shrink-0 flex-col items-center justify-between gap-2 rounded border bg-elev-2 px-1 py-2 text-[11px] font-semibold tracking-wider text-muted-foreground uppercase hover:bg-accent/40"
				style:border-color="hsl(var(--border))"
				data-testid="workbench-tree-inspector-expand"
				title="Expand inspector"
			>
				<ChevronLeft class="h-3 w-3" aria-hidden="true" />
				<span class="[transform:rotate(180deg)] [writing-mode:vertical-rl]"> Inspector </span>
				<span></span>
			</button>
		{:else}
			<div
				class="flex min-h-0 flex-col rounded border bg-elev-2"
				style:flex="{treeInspectorFraction} 1 0"
				style:border-color="hsl(var(--border))"
				data-testid="workbench-tree-inspector"
			>
				<!-- Header mirrors the Columns bottom-inspector
								     header — INSPECTOR label + collapse
								     chevron (points right = collapse-to-right). -->
				<header
					class="flex shrink-0 items-center justify-between gap-2 border-b bg-elev-1 px-3 py-1.5"
					style:border-color="hsl(var(--border))"
				>
					<span
						class="text-[11px] font-semibold tracking-wider uppercase"
						style:color="hsl(var(--muted-foreground))"
					>
						Inspector
					</span>
					<button
						type="button"
						onclick={() => (treeInspectorCollapsed = true)}
						class="shrink-0 rounded p-1 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
						aria-label="Collapse inspector"
						title="Collapse inspector"
						data-testid="workbench-tree-inspector-collapse"
					>
						<ChevronRight class="h-3 w-3" aria-hidden="true" />
					</button>
				</header>
				<!-- Body: tabs + content inside p-3 padded flex column,
								     mirroring the dim frame pattern (header → whitespace
								     → content). -->
				<div class="flex min-h-0 flex-1 flex-col gap-3 overflow-hidden p-3">
					<div class="flex shrink-0">
						{@render inspectorTabStrip('workbench-tree-inspector-tab')}
					</div>
					<!-- Content area: `overflow-hidden` + `min-w-0` clip horizontal
									     overflow so a wide Sample-data table doesn't push the tab
									     strip + drawer wider than the inspector's actual width.
									     Vertical overflow handled here (drawer is a bottom row). -->
					<div class="min-h-0 min-w-0 flex-1 overflow-x-hidden overflow-y-auto">
						{#if inspectorTab === 'properties'}
							{@render inspectorProperties()}
						{:else}
							{@render inspectorCode()}
						{/if}
					</div>
				</div>
			</div>
		{/if}
	</div>
{/snippet}

<!-- ── Inspector tab strip — collapses to a styled dropdown when the
     drawer is narrower than ~420px.  The dropdown gets a distinct
     primary-bordered "View" label so it reads as a different control
     than the row of buttons, not just a shrunken one.  Used by both
     the Tree-view inspector and the Columns-view drawer.  idPrefix
     parameter scopes data-testids so the existing Playwright selectors
     ("workbench-inspector-tab-*" vs "workbench-tree-inspector-tab-*")
     keep working. ──────────────────────────────────────────────────── -->
{#snippet inspectorTabStrip(idPrefix: string)}
	<div class="@container/insptabs w-full">
		<!-- Wide: understated underlined tabs.  Sample-data / Try-a-query /
		     Cubes moved to the top-level `Validate` tab so those wide result
		     tables get the whole workbench area instead of being crammed
		     into the drawer. -->
		<div
			class="flex w-full items-end gap-4 border-b @max-[420px]/insptabs:hidden"
			style:border-color="hsl(var(--border))"
			role="tablist"
		>
			<button
				type="button"
				role="tab"
				aria-selected={inspectorTab === 'properties'}
				onclick={() => (inspectorTab = 'properties')}
				class="-mb-px inline-flex items-center gap-1 border-b-2 px-1 pb-1.5 text-[11px] transition-colors {inspectorTab ===
				'properties'
					? 'border-primary text-foreground'
					: 'border-transparent text-muted-foreground hover:text-foreground'}"
				data-testid="{idPrefix}-properties"
			>
				Properties
			</button>
			<button
				type="button"
				role="tab"
				aria-selected={inspectorTab === 'code'}
				onclick={() => (inspectorTab = 'code')}
				class="-mb-px inline-flex items-center gap-1 border-b-2 px-1 pb-1.5 font-mono text-[11px] transition-colors {inspectorTab ===
				'code'
					? 'border-primary text-foreground'
					: 'border-transparent text-muted-foreground hover:text-foreground'}"
				data-testid="{idPrefix}-code"
			>
				&lt;/Code&gt;
			</button>
		</div>
		<!-- Narrow: dropdown picker.  Primary-bordered "View" pill on the
		     left signals "this is a switcher, not a single button"; the
		     selected tab name fills the rest.  Uses a native <select> so
		     keyboard / VO behaviour is free. -->
		<div
			class="hidden w-full items-stretch overflow-hidden rounded border @max-[420px]/insptabs:flex"
			style:border-color="hsl(var(--primary))"
		>
			<span
				class="flex shrink-0 items-center px-2 py-1 text-[10px] font-semibold tracking-wider uppercase"
				style:color="hsl(var(--primary))"
				style:background-color="hsl(var(--primary) / 0.1)"
			>
				View
			</span>
			<select
				bind:value={inspectorTab}
				class="flex-1 cursor-pointer bg-transparent px-2 py-1 text-[11px] font-semibold focus:outline-none"
				data-testid="{idPrefix}-select"
				aria-label="Inspector view"
			>
				<option value="properties">Properties</option>
				<option value="code">&lt;/Code&gt;</option>
			</select>
		</div>
	</div>
{/snippet}

<!-- ── Inspector panel (right-hand drawer in Columns view) ─────────── -->
{#snippet inspectorPanel()}
	<div
		class="flex h-full min-h-0 w-full flex-col overflow-hidden rounded border bg-elev-2"
		style:border-color="hsl(var(--border))"
		data-testid="workbench-inspector"
	>
		<!-- Header bar.  Collapse chevron sits on the LEFT (the pane-facing
		     edge of the drawer) so it lands in the same visual spot as the
		     expand chevron on the collapsed strip — the button doesn't
		     move as you toggle expanded/collapsed.  Maximize + dock-side
		     toggle live on the right, since they're independent of the
		     open/close motion. -->
		<header
			class="flex shrink-0 items-center gap-2 border-b bg-elev-1 px-3 py-1.5"
			style:border-color="hsl(var(--border))"
		>
			<button
				type="button"
				onclick={() => {
					inspectorMaximized = false;
					inspectorCollapsed = true;
				}}
				class="shrink-0 rounded p-1 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
				aria-label="Collapse inspector drawer"
				title="Collapse drawer"
				data-testid="workbench-inspector-collapse"
			>
				<!-- Chevron matches the collapsed-strip's chevron:
				     ChevronUp for bottom-dock, ChevronLeft for right-dock. -->
				{#if inspectorPosition === 'bottom'}
					<ChevronDown class="h-3 w-3" aria-hidden="true" />
				{:else}
					<ChevronRight class="h-3 w-3" aria-hidden="true" />
				{/if}
			</button>
			<span class="text-[11px] font-semibold tracking-wider uppercase"> Inspector </span>
			<div class="ml-auto flex items-center gap-1">
				<button
					type="button"
					onclick={() => (inspectorMaximized = !inspectorMaximized)}
					class="shrink-0 rounded p-1 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
					aria-label={inspectorMaximized ? 'Restore inspector drawer' : 'Maximize inspector drawer'}
					title={inspectorMaximized ? 'Restore drawer' : 'Maximize drawer'}
					data-testid="workbench-inspector-maximize"
				>
					{#if inspectorMaximized}
						<Minimize2 class="h-3 w-3" aria-hidden="true" />
					{:else}
						<Maximize2 class="h-3 w-3" aria-hidden="true" />
					{/if}
				</button>
				<!-- Dock-side toggle — DevTools-style "put me on the side vs
				     the bottom".  Icon shows the OTHER position (what you'll
				     get if you click). -->
				<button
					type="button"
					onclick={() => (inspectorPosition = inspectorPosition === 'bottom' ? 'right' : 'bottom')}
					class="shrink-0 rounded p-1 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
					aria-label={inspectorPosition === 'bottom'
						? 'Dock inspector to right'
						: 'Dock inspector to bottom'}
					title={inspectorPosition === 'bottom' ? 'Dock to side' : 'Dock to bottom'}
					data-testid="workbench-inspector-position"
				>
					{#if inspectorPosition === 'bottom'}
						<PanelRight class="h-3 w-3" aria-hidden="true" />
					{:else}
						<PanelBottom class="h-3 w-3" aria-hidden="true" />
					{/if}
				</button>
			</div>
		</header>
		<!-- Body: tab strip + content inside a p-3 padded flex column,
			     mirroring the "Create Your Dimensions & Hierarchies" frame's
			     header → whitespace → content pattern.  Tabs are no longer
			     sub-chrome (no border-b); they sit inside the padded area
			     with a gap-3 between them and the content. -->
		<div class="flex min-h-0 flex-1 flex-col gap-3 overflow-hidden p-3">
			<div class="flex shrink-0">
				{@render inspectorTabStrip('workbench-inspector-tab')}
			</div>
			<!-- Content area: `overflow-hidden` + `min-w-0` clip horizontal
			     overflow so a wide Sample-data table doesn't push the tab strip
			     + drawer wider than the inspector's actual width.  Vertical
			     overflow is handled here (drawer is now a bottom row and
			     content can grow past its height). -->
			<div class="min-h-0 min-w-0 flex-1 overflow-x-hidden overflow-y-auto">
				{#if inspectorTab === 'properties'}
					{@render inspectorProperties()}
				{:else}
					{@render inspectorCode()}
				{/if}
			</div>
		</div>
	</div>
{/snippet}

<!-- Inspector › Properties tab.  Thin wrapper mounting the extracted
     InspectorProperties.svelte (#1039 stage 2) so both render sites
     (columns inspector + tree inspector) stay `{@render inspectorProperties()}`
     and the shell keeps ONE source of truth for the props/bind wiring. -->
{#snippet inspectorProperties()}
	<InspectorProperties
		{store}
		{cubes}
		{selectedCubeId}
		{factsMeasureGroups}
		{selectedDimension}
		{selectedHierarchy}
		{selectedMeasure}
		{selectedAttribute}
		{selectedLevel}
		{selectedTreeMeasureGroup}
		{selectedTreeCube}
		{hierarchyExplicitlyClicked}
		{renameCube}
		{selectTreeMeasureGroup}
		{addMeasureGroupToCube}
		{renameHierarchy}
		{commitHierarchyName}
		{renameDimension}
		{commitDimensionName}
		{tableNameFor}
		{columnTypeFor}
	/>
{/snippet}

{#snippet inspectorCode()}
	{@const xml = workbenchPreviewXml()}
	{@const code = codeFormat === 'yaml' ? mondrianXmlToYaml(xml) : xml}
	{@const html = codeFormat === 'yaml' ? highlightYaml(code) : highlightXml(code)}
	<!-- Relative wrapper hosts the floating format toggle + Copy button.  The
	     <pre> scrolls inside it; the controls stay pinned to the top-right. -->
	<div class="relative h-full">
		<!-- The <pre> body MUST hug {@html} with no surrounding whitespace: a
		     <pre> preserves it, so a newline/indent before the content would
		     push the FIRST rendered line right (reported: `schema:` over-indented
		     while later lines sat correctly). eslint-disable is block-form + outside
		     the <pre> for the same reason (a comment inside would leak whitespace). -->
		<!-- eslint-disable svelte/no-at-html-tags -->
		<pre
			class="xml-code h-full overflow-auto p-3 font-mono text-[11px] leading-relaxed break-words whitespace-pre-wrap"
			style:background="hsl(var(--background))"
			data-testid="workbench-inspector-code">{@html html}</pre>
		<!-- eslint-enable svelte/no-at-html-tags -->
		<!-- XML / YAML toggle (#1080).  Both render from the one M4 XML emitter
		     (YAML derived via mondrianXmlToYaml) so they never disagree. -->
		<div
			class="absolute top-2 right-20 z-10 inline-flex overflow-hidden rounded border text-[10px] font-medium shadow-sm"
			style:border-color="hsl(var(--border))"
			data-testid="workbench-inspector-code-format"
		>
			<button
				type="button"
				onclick={() => (codeFormat = 'xml')}
				class="px-2 py-1 {codeFormat === 'xml'
					? 'bg-primary text-primary-foreground'
					: 'bg-elev-2 text-muted-foreground hover:bg-accent/40'}"
				data-testid="workbench-inspector-code-format-xml"
				title="Show as Mondrian 4 XML">XML</button
			>
			<button
				type="button"
				onclick={() => (codeFormat = 'yaml')}
				class="px-2 py-1 {codeFormat === 'yaml'
					? 'bg-primary text-primary-foreground'
					: 'bg-elev-2 text-muted-foreground hover:bg-accent/40'}"
				data-testid="workbench-inspector-code-format-yaml"
				title="Show as Mondrian 4 YAML">YAML</button
			>
		</div>
		<button
			type="button"
			onclick={() => copyInspectorCode(code)}
			class="absolute top-2 right-2 z-10 inline-flex items-center gap-1 rounded border bg-elev-2 px-2 py-1 text-[10px] font-medium text-muted-foreground shadow-sm hover:bg-accent/40"
			style:border-color="hsl(var(--border))"
			data-testid="workbench-inspector-code-copy"
			title={codeFormat === 'yaml' ? 'Copy YAML' : 'Copy XML'}
		>
			{#if codeCopyFlash}
				<Check class="h-3 w-3 text-primary" aria-hidden="true" />
				<span class="text-primary">Copied</span>
			{:else}
				<Copy class="h-3 w-3" aria-hidden="true" />
				<span>Copy</span>
			{/if}
		</button>
	</div>
{/snippet}

<!--
	Sample data tab.  Mirrors the AI-wizard's per-cube "Sample data" tab
	(see `/schemas/new`).  When the canvas has a fact table picked + a
	connection, fetches 5 rows from /api/inference/sample/<connId> and
	renders a compact table.  Otherwise: empty state listing what the
	user needs to do.
-->
{#snippet inspectorSampleData()}
	<div class="flex h-full flex-col gap-3 p-3 text-xs" data-testid="workbench-inspector-sample-data">
		{#if !sampleDataReadiness.ready}
			<div class="flex flex-1 flex-col items-center justify-center gap-2 text-center">
				<p class="text-xs font-semibold">Sample data isn't ready yet</p>
				<p class="text-[11px]" style="color: hsl(var(--muted-foreground))">
					Finish the cube before sample rows will populate:
				</p>
				<ul class="flex flex-col gap-1 text-left text-[11px]">
					{#each sampleDataReadiness.missing as item (item)}
						<li class="flex items-center gap-1.5">
							<span
								class="inline-block h-1.5 w-1.5 shrink-0 rounded-full"
								style:background-color="hsl(var(--primary))"
							></span>
							{item}
						</li>
					{/each}
				</ul>
			</div>
		{:else}
			<header class="flex shrink-0 items-center justify-between gap-2">
				<div class="flex min-w-0 flex-1 flex-col gap-0.5">
					<span class="truncate text-xs font-semibold">
						{cubeFactTable?.schema
							? `${cubeFactTable.schema}.${cubeFactTable.name}`
							: cubeFactTable?.name}
					</span>
					<span class="text-[10px]" style:color="hsl(var(--muted-foreground))">
						First {sampleRowLimit} row{sampleRowLimit === 1 ? '' : 's'} from the fact table
					</span>
				</div>
				<!-- Row-count chips + Refresh.  Picking a chip re-keys the
				     fetch (see sampleDataKey) so the effect refires. -->
				<div class="flex shrink-0 items-center gap-2">
					<div
						class="inline-flex overflow-hidden rounded border"
						style:border-color="hsl(var(--border))"
						role="radiogroup"
						aria-label="Number of sample rows"
					>
						{#each SAMPLE_ROW_LIMITS as n (n)}
							<button
								type="button"
								role="radio"
								aria-checked={sampleRowLimit === n}
								onclick={() => (sampleRowLimit = n)}
								class="border-l px-2 py-0.5 text-[10px] font-semibold tracking-wide first:border-l-0 {sampleRowLimit ===
								n
									? 'bg-primary/10 text-primary'
									: 'text-muted-foreground hover:bg-accent/40 hover:text-foreground'}"
								style:border-color="hsl(var(--border))"
								data-testid="sample-data-row-chip"
								data-row-count={n}
								title={`Show ${n} row${n === 1 ? '' : 's'}`}
							>
								{n}
							</button>
						{/each}
					</div>
					<button
						type="button"
						onclick={() => {
							sampleDataKey = null;
							void loadSampleData();
						}}
						class="rounded border px-2 py-0.5 text-[10px] font-semibold tracking-wide uppercase hover:bg-accent hover:text-accent-foreground"
						style:border-color="hsl(var(--border))"
						title="Refresh sample data"
					>
						Refresh
					</button>
				</div>
			</header>
			{#if sampleData.kind === 'idle' || sampleData.kind === 'loading'}
				<p class="text-[11px]" style:color="hsl(var(--muted-foreground))">Loading sample…</p>
			{:else if sampleData.kind === 'error'}
				<p
					class="rounded border p-2 text-[11px] text-destructive"
					style:border-color="hsl(var(--destructive))"
				>
					{sampleData.message}
				</p>
			{:else}
				<!-- min-w-full lets the table grow to its natural content width
				     so the container's overflow-auto fires when columns add up
				     to more than the inspector's width.  table-auto = columns
				     size to their content rather than splitting the container
				     equally. -->
				<div
					class="min-h-0 flex-1 overflow-auto rounded border"
					style:border-color="hsl(var(--border))"
				>
					<table class="min-w-full table-auto font-mono text-[10px]">
						<thead class="sticky top-0 bg-elev-1">
							<tr>
								{#each sampleData.columns as col (col)}
									<th
										class="border-b px-2 py-1 text-left font-semibold whitespace-nowrap"
										style:border-color="hsl(var(--border))"
									>
										{col}
									</th>
								{/each}
							</tr>
						</thead>
						<tbody>
							{#each sampleData.rows as row, i (i)}
								<tr class="border-b" style:border-color="hsl(var(--border))">
									{#each sampleData.columns as col (col)}
										<td class="px-2 py-1 whitespace-nowrap">
											{row[col] === null || row[col] === undefined ? '—' : String(row[col])}
										</td>
									{/each}
								</tr>
							{/each}
						</tbody>
					</table>
				</div>
			{/if}
		{/if}
	</div>
{/snippet}

<!--
	Try a query tab.  Posts the workbench's MDX scaffold + a proposal
	shape to /api/inference/try-query (originally built for AI-draft
	cubes; same shape works for the workbench-authored ones too).
	Renders the result inline as a flat table.
-->
{#snippet inspectorTryQuery()}
	{@const previewMdx = tryQueryEffectiveMdx}
	{@const measureName = tryQueryPickedMeasure}
	{@const levelName = tryQueryPickedLevel?.name}
	<div class="flex h-full flex-col gap-3 p-3 text-xs" data-testid="workbench-inspector-try-query">
		{#if !tryQueryReadiness.ready}
			<div class="flex flex-1 flex-col items-center justify-center gap-2 text-center">
				<p class="text-xs font-semibold">Try a query isn't ready yet</p>
				<p class="text-[11px]" style:color="hsl(var(--muted-foreground))">
					Finish the cube before you can run a test query:
				</p>
				<ul class="flex flex-col gap-1 text-left text-[11px]">
					{#each tryQueryReadiness.missing as item (item)}
						<li class="flex items-center gap-1.5">
							<span
								class="inline-block h-1.5 w-1.5 shrink-0 rounded-full"
								style:background-color="hsl(var(--primary))"
							></span>
							{item}
						</li>
					{/each}
				</ul>
			</div>
		{:else if previewMdx}
			<!-- Header: MDX label + Copy + Run.  Open in Saiku lives on
			     each cube row in the left rail — no need to repeat it
			     here. -->
			<div class="flex shrink-0 items-center justify-between gap-2">
				<span
					class="inline-flex items-center gap-1 text-[10px] font-semibold tracking-wider uppercase"
					style:color="hsl(var(--muted-foreground))"
				>
					MDX
					<span class="ml-1 normal-case opacity-70">
						· {measureName} by {levelName}
					</span>
					{#if tryQueryMdxOverride !== null}
						<span
							class="ml-1 rounded bg-warning/20 px-1 text-[9px] font-medium text-warning normal-case"
							title="MDX has been hand-edited"
						>
							edited
						</span>
					{/if}
				</span>
				<div class="flex shrink-0 items-center gap-1.5">
					{#if tryQueryMdxOverride !== null}
						<button
							type="button"
							onclick={() => (tryQueryMdxOverride = null)}
							class="text-[10px] font-medium text-primary hover:underline"
							data-testid="workbench-try-query-mdx-reset"
							title="Discard edits and restore the auto-generated MDX"
						>
							Reset
						</button>
					{/if}
					<button
						type="button"
						onclick={() => {
							if (typeof navigator !== 'undefined' && navigator.clipboard) {
								void navigator.clipboard.writeText(previewMdx);
							}
						}}
						class="inline-flex items-center gap-1 rounded border px-2 py-0.5 text-[10px] font-semibold tracking-wider uppercase hover:bg-accent/40"
						style:border-color="hsl(var(--border))"
						data-testid="workbench-try-query-copy"
					>
						Copy
					</button>
					<button
						type="button"
						onclick={() => void runTryQuery()}
						disabled={tryQueryState.kind === 'loading'}
						class="inline-flex items-center gap-1 rounded bg-primary px-2 py-0.5 text-[10px] font-semibold tracking-wider text-primary-foreground uppercase hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
						data-testid="workbench-try-query-run"
					>
						{tryQueryState.kind === 'loading' ? 'Running…' : 'Run'}
					</button>
				</div>
			</div>
			<!-- MDX textarea — visible + editable by default.  Typing seeds
			     tryQueryMdxOverride; Run uses the textarea contents; Reset
			     (header) drops the override and snaps back to the seed. -->
			<textarea
				class="shrink-0 resize-y rounded border bg-elev-1 p-2 font-mono text-[10px] leading-relaxed focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
				style:border-color="hsl(var(--border))"
				style:min-height="6rem"
				style:max-height="16rem"
				rows="5"
				spellcheck="false"
				value={previewMdx}
				oninput={(e) => (tryQueryMdxOverride = (e.currentTarget as HTMLTextAreaElement).value)}
				data-testid="workbench-try-query-mdx"
			></textarea>

			{#if tryQueryState.kind === 'idle'}
				<div class="flex flex-1 items-center justify-center text-center">
					<p class="text-[11px]" style:color="hsl(var(--muted-foreground))">
						Hit <strong>Run</strong> to execute the MDX against the unsaved cube.
					</p>
				</div>
			{:else if tryQueryState.kind === 'loading'}
				<div class="flex flex-1 items-center justify-center">
					<p class="text-[11px]" style:color="hsl(var(--muted-foreground))">Running query…</p>
				</div>
			{:else if tryQueryState.kind === 'error'}
				<div
					class="rounded border border-destructive/40 bg-destructive/10 p-2 text-[11px] text-destructive"
					data-testid="workbench-try-query-error"
				>
					{tryQueryState.message}
				</div>
			{:else}
				<div class="flex shrink-0 items-center justify-between gap-2">
					<span
						class="text-[10px] font-semibold tracking-wider uppercase"
						style:color="hsl(var(--muted-foreground))"
					>
						Result · {tryQueryState.rows.length} row{tryQueryState.rows.length === 1 ? '' : 's'}
						{#if tryQueryState.durationMs !== undefined}
							<span class="ml-1 normal-case opacity-70">({tryQueryState.durationMs}ms)</span>
						{/if}
					</span>
					{#if tryQueryState.truncated}
						<span
							class="rounded border border-warning/40 bg-warning/10 px-1.5 text-[9px] text-warning"
						>
							truncated
						</span>
					{/if}
				</div>
				<div
					class="min-h-0 flex-1 overflow-auto rounded border bg-elev-1"
					style:border-color="hsl(var(--border))"
					data-testid="workbench-try-query-result"
				>
					{#if tryQueryState.rows.length === 0}
						<p class="p-3 text-center text-[11px]" style:color="hsl(var(--muted-foreground))">
							No rows returned.
						</p>
					{:else}
						<table class="min-w-full table-auto font-mono text-[10px]">
							<thead class="sticky top-0 bg-elev-1">
								<tr>
									{#each tryQueryState.columns as col, i (i)}
										<th
											class="border-b px-2 py-1 text-left font-semibold whitespace-nowrap"
											style:border-color="hsl(var(--border))"
										>
											{col}
										</th>
									{/each}
								</tr>
							</thead>
							<tbody>
								{#each tryQueryState.rows as row, i (i)}
									<tr class="border-b" style:border-color="hsl(var(--border))">
										{#each tryQueryState.columns as col, j (j)}
											<td class="px-2 py-1 whitespace-nowrap">
												{row[col] === null || row[col] === undefined ? '—' : String(row[col])}
											</td>
										{/each}
									</tr>
								{/each}
							</tbody>
						</table>
					{/if}
				</div>
			{/if}
		{:else}
			<div class="flex flex-1 flex-col items-center justify-center gap-2 text-center">
				<p class="text-xs font-semibold">Cube is ready</p>
				<p class="text-[11px]" style:color="hsl(var(--muted-foreground))">
					Pick at least one measure + one dimension level to generate a sample query.
				</p>
			</div>
		{/if}
	</div>
{/snippet}

<!-- ── Delete confirmation modal ────────────────────────────────── -->
{#if confirmDelete}
	<div
		class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
		role="dialog"
		aria-modal="true"
		aria-labelledby="confirm-delete-title"
		data-testid="workbench-confirm-delete"
	>
		<button
			type="button"
			class="absolute inset-0 cursor-default"
			aria-label="Cancel"
			onclick={cancelDeletion}
		></button>
		<div
			class="relative z-10 w-full max-w-md rounded-lg border bg-elev-4 p-5 shadow-xl"
			style:border-color="hsl(var(--border))"
		>
			<h3
				id="confirm-delete-title"
				class="mb-3 text-sm font-semibold"
				style:color="hsl(var(--foreground))"
			>
				{confirmDelete.title}
			</h3>
			<p
				class="mb-2 text-[11px] tracking-wider uppercase"
				style:color="hsl(var(--muted-foreground))"
			>
				What you'll lose
			</p>
			<ul class="mb-4 list-disc space-y-1 pl-5 text-xs" style:color="hsl(var(--foreground))">
				{#each confirmDelete.impact as line, i (i)}
					<li>{line}</li>
				{/each}
			</ul>
			<div class="flex justify-end gap-2">
				<button
					type="button"
					onclick={cancelDeletion}
					class="rounded border border-border px-3 py-1.5 text-xs font-medium hover:bg-accent hover:text-accent-foreground"
					data-testid="workbench-confirm-delete-cancel"
				>
					Cancel
				</button>
				<button
					type="button"
					onclick={confirmDeletion}
					class="rounded border border-destructive bg-destructive px-3 py-1.5 text-xs font-medium text-destructive-foreground hover:opacity-90"
					data-testid="workbench-confirm-delete-confirm"
				>
					Delete
				</button>
			</div>
		</div>
	</div>
{/if}

<!-- ── Attribute-removal confirmation modal ─────────────────────── -->
{#if confirmRemoveAttr}
	<div
		class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
		role="dialog"
		aria-modal="true"
		aria-labelledby="confirm-remove-attr-title"
		data-testid="workbench-confirm-remove-attribute"
	>
		<button
			type="button"
			class="absolute inset-0 cursor-default"
			aria-label="Cancel"
			onclick={cancelRemoveAttribute}
		></button>
		<div
			class="relative z-10 w-full max-w-md rounded-lg border bg-elev-4 p-5 shadow-xl"
			style:border-color="hsl(var(--border))"
		>
			<h3
				id="confirm-remove-attr-title"
				class="mb-3 text-sm font-semibold"
				style:color="hsl(var(--foreground))"
			>
				Remove attribute "{confirmRemoveAttr.columnName}"?
			</h3>
			<p class="mb-2 text-xs" style:color="hsl(var(--foreground))">
				{confirmRemoveAttr.affected.length} hierarch{confirmRemoveAttr.affected.length === 1
					? 'y depends'
					: 'ies depend'} on this attribute. Removing it will also remove the matching level from:
			</p>
			<ul class="mb-4 list-disc space-y-1 pl-5 text-xs" style:color="hsl(var(--foreground))">
				{#each confirmRemoveAttr.affected as a (a.hierId)}
					<li>{a.hierName}</li>
				{/each}
			</ul>
			<div class="flex justify-end gap-2">
				<button
					type="button"
					onclick={cancelRemoveAttribute}
					class="rounded border border-border px-3 py-1.5 text-xs font-medium hover:bg-accent hover:text-accent-foreground"
					data-testid="workbench-confirm-remove-attr-cancel"
				>
					Cancel
				</button>
				<button
					type="button"
					onclick={confirmRemoveAttribute}
					class="rounded border border-destructive bg-destructive px-3 py-1.5 text-xs font-medium text-destructive-foreground hover:opacity-90"
					data-testid="workbench-confirm-remove-attr-confirm"
				>
					Remove
				</button>
			</div>
		</div>
	</div>
{/if}

<style>
	/* Resize handle (between top + bottom workbench sections) — grip
	   colour and hover lift, distinct from default border so it reads
	   in both themes. */
	:global(.workbench-resize-handle) > div {
		background-color: hsl(var(--border));
		transition: background-color 120ms ease;
	}
	:global(.workbench-resize-handle):hover > div {
		background-color: hsl(var(--primary));
	}
	:global(.workbench-resize-handle-active) > div {
		background-color: hsl(var(--primary));
	}
	:global([data-theme='dark'] .workbench-resize-handle) > div {
		background-color: hsl(220 10% 45%);
	}
	:global([data-theme='dark'] .workbench-resize-handle):hover > div {
		background-color: hsl(220 90% 65%);
	}

	/* Softer "gated" appearance for a whole pane section — used on
	   the Hierarchies pane while Attributes is mid-edit.  Reads as
	   "not clickable yet" without going invisible; opacity is high
	   enough that headings, prompt copy and icons stay legible so
	   the user can still see what's waiting for them. */
	:global(.workbench-pane-disabled) {
		opacity: 0.65;
		pointer-events: none;
	}
	:global([data-theme='dark'] .workbench-pane-disabled) {
		opacity: 0.6;
	}

	/* Surface elevation overrides moved to layout.css via the
	   --elev-0…--elev-4 scale.  Workbench now uses bg-elev-* classes
	   directly so light + dark modes share the same role mapping. */

	/* XML syntax highlighting palette — IDE-style: tag (blue), attr
	   (warm yellow), string (green), comment (muted grey). */
	:global(.xml-code .xml-tag) {
		color: hsl(210 80% 50%);
		font-weight: 600;
	}
	:global(.xml-code .xml-attr) {
		color: hsl(35 80% 45%);
	}
	:global(.xml-code .xml-string) {
		color: hsl(140 55% 40%);
	}
	:global(.xml-code .xml-comment) {
		color: hsl(0 0% 50%);
		font-style: italic;
	}
	:global([data-theme='dark'] .xml-code .xml-tag) {
		color: hsl(210 80% 65%);
	}
	:global([data-theme='dark'] .xml-code .xml-attr) {
		color: hsl(35 90% 65%);
	}
	:global([data-theme='dark'] .xml-code .xml-string) {
		color: hsl(140 55% 60%);
	}
	:global([data-theme='dark'] .xml-code .xml-comment) {
		color: hsl(0 0% 55%);
	}

	/* Selected-row appearance matches the canvas "highlighted column"
	   treatment.  Light mode: pale mint bg + deep mint text.  Dark
	   mode: bright emerald bg + black text.  Same HSL values the
	   TableNode uses so the workbench reads consistent with the
	   canvas selection. */
	:global(.workbench-row-selected) {
		background-color: hsl(150 55% 88%);
		color: hsl(150 55% 22%);
		border-color: hsl(150 55% 35%);
	}
	:global([data-theme='dark'] .workbench-row-selected) {
		background-color: hsl(150 55% 62%);
		color: hsl(0 0% 6%);
		border-color: hsl(150 55% 30%);
	}
	/* Context state — "last selected in this pane, but focus has moved
	   elsewhere."  Muted grey so it doesn't compete with the bright
	   green of the actively-focused row.  Same in both themes: a soft
	   muted surface + border, no color shift on the text.  Fixes the
	   "everything looks selected" bug when multiple panes each held
	   their own bright highlight. */
	:global(.workbench-row-context) {
		background-color: hsl(var(--muted));
		border-color: hsl(var(--border));
	}
	/* Outline-only variant — same green border, no fill.  Used on
	   calc cards where filling the whole card would compete with
	   the chip/textarea content inside. */
	:global(.workbench-row-selected-outline) {
		border-color: hsl(150 55% 35%);
		box-shadow: 0 0 0 1px hsl(150 55% 35%);
	}
	:global([data-theme='dark'] .workbench-row-selected-outline) {
		border-color: hsl(150 55% 50%);
		box-shadow: 0 0 0 1px hsl(150 55% 50%);
	}
	/* Sub-labels inside a selected row inherit the row color but drop
	   opacity so they stay legible without their original
	   muted-foreground gray (which disappears on green). */
	:global(.workbench-row-selected *[style*='muted-foreground']),
	:global(.workbench-row-selected .text-muted-foreground) {
		color: inherit !important;
		opacity: 0.7;
	}
	/* Number badges keep their accent bg / fg so they stay visible. */
	:global(.workbench-row-selected .bg-accent) {
		background-color: hsl(150 55% 22%);
		color: hsl(150 55% 88%);
	}
	/* Editable inputs inside a selected (mint) row get a plain white
	   surface with dark text in light mode — the row's mint fill is
	   pale enough that a white input reads cleanly as a distinct
	   editable affordance.  Dark mode keeps a dark-grey surface with
	   white text so the input contrasts against the bright emerald
	   selected-row fill. */
	:global(.workbench-row-selected input[type='text']),
	:global(.workbench-row-selected textarea) {
		background-color: hsl(0 0% 100%);
		color: hsl(220 12% 18%);
		border-color: hsl(220 12% 70%);
	}
	:global(.workbench-row-selected input[type='text']::placeholder),
	:global(.workbench-row-selected textarea::placeholder) {
		color: hsl(220 8% 55%);
	}
	:global([data-theme='dark'] .workbench-row-selected input[type='text']),
	:global([data-theme='dark'] .workbench-row-selected textarea) {
		background-color: hsl(220 10% 28%);
		color: hsl(0 0% 100%);
		border-color: hsl(220 12% 18%);
	}
	:global([data-theme='dark'] .workbench-row-selected input[type='text']::placeholder),
	:global([data-theme='dark'] .workbench-row-selected textarea::placeholder) {
		color: hsl(220 8% 65%);
	}
	:global(.workbench-row-selected input[type='text']::selection),
	:global(.workbench-row-selected textarea::selection) {
		background-color: hsl(210 90% 60%);
		color: hsl(0 0% 100%);
	}

	/* System-dark fallback — when no explicit data-theme attr is set
	   but the OS prefers dark, mirror every hardcoded
	   `[data-theme='dark']` rule above.  Without these the workbench
	   reads as a light-mode bleed (pale mint row, blue xml tags, etc)
	   against an otherwise-dark surface.  The `:not([data-theme='light'])`
	   keeps explicit light override winning over the OS pref. */
	@media (prefers-color-scheme: dark) {
		:global(:root:not([data-theme='light']) .workbench-resize-handle) > div {
			background-color: hsl(220 10% 45%);
		}
		:global(:root:not([data-theme='light']) .workbench-resize-handle):hover > div {
			background-color: hsl(220 90% 65%);
		}
		:global(:root:not([data-theme='light']) .workbench-pane-disabled) {
			opacity: 0.6;
		}
		:global(:root:not([data-theme='light']) .xml-code .xml-tag) {
			color: hsl(210 80% 65%);
		}
		:global(:root:not([data-theme='light']) .xml-code .xml-attr) {
			color: hsl(35 90% 65%);
		}
		:global(:root:not([data-theme='light']) .xml-code .xml-string) {
			color: hsl(140 55% 60%);
		}
		:global(:root:not([data-theme='light']) .xml-code .xml-comment) {
			color: hsl(0 0% 55%);
		}
		:global(:root:not([data-theme='light']) .workbench-row-selected) {
			background-color: hsl(150 55% 62%);
			color: hsl(0 0% 6%);
			border-color: hsl(150 55% 30%);
		}
		:global(:root:not([data-theme='light']) .workbench-row-selected-outline) {
			border-color: hsl(150 55% 50%);
			box-shadow: 0 0 0 1px hsl(150 55% 50%);
		}
	}
</style>
