/**
 * Canvas schema designer — reactive state container.
 *
 * One `SchemaCanvasStore` per page mount. Owns the canvas document (tables /
 * joins / groups), the active selection, and the workbench cursor.
 * Persists every mutation to localStorage under
 * `saiku.canvas.<connectionId>` so reloads pick up where the user left
 * off. Server-side persistence is layered on later via the existing
 * /me/schemas endpoint.
 *
 * Svelte 5 reactivity — uses the runes API (`$state` inside `.svelte.ts`
 * classes is supported). Components consume via instance properties; no
 * separate subscribe API needed.
 */
import type {
  SchemaCanvasState,
  SchemaCanvasTable,
  SchemaCanvasJoin,
  SchemaCanvasGroup,
  SourceTableCandidate,
  SchemaCanvasTableRole,
  SchemaCanvasJoinKind,
  SchemaCanvasDimension,
  SchemaCanvasHierarchy,
  SchemaCanvasLevel,
  SchemaCanvasMeasure,
  SchemaCanvasColumnKind,
  SchemaCanvasCube,
  SchemaCanvasMeasureGroup,
  SchemaCanvasDimensionLink,
  SchemaCanvasCalc,
  SchemaCanvasTimeCalc,
} from "./types.js";
import { suggestJoins } from "./auto-suggest.js";

const STORAGE_PREFIX = "saiku.canvas.";
/** Legacy localStorage key the Facts & Measures workbench used to own the
 *  cube model under before it was lifted into the doc (`doc.cubes`).  Still
 *  read once on load to migrate any state authored before the refactor. */
const LEGACY_CUBES_STORAGE_KEY = "saiku.workbench.cubesState";
/** Per-user UI preferences (NOT per-connection — survives connection
 *  switches and applies across every cube the user authors).  Kept in
 *  its own localStorage key so doc-shape changes don't risk losing
 *  preference state. */
const PREFS_STORAGE_KEY = "saiku.canvas.prefs";

type LayoutMode = "layered" | "star" | "grid" | "byJoins";

/** Top-level surface the schema designer renders.  `'binary'` is a
 *  historical alias for `'workbench'` (single "Cube Workbench" tab
 *  that owned dims + facts) — migrated on load. */
export type CanvasMode = "canvas" | "workbench" | "facts" | "validate";

interface CanvasPrefs {
  defaultLayoutMode?: LayoutMode;
  /** Which view tab the user was last on — restored on next mount so
   *  someone who flipped to a designer tab last session lands
   *  back there instead of always landing on the Schema Canvas. */
  mode?: CanvasMode | "binary";
}

function migrateMode(
  mode: CanvasMode | "binary" | undefined,
): CanvasMode | undefined {
  if (!mode) return undefined;
  if (mode === "binary") return "workbench";
  return mode;
}

function loadPrefsFromStorage(): CanvasPrefs {
  if (typeof window === "undefined") return {};
  try {
    const raw = window.localStorage.getItem(PREFS_STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as CanvasPrefs;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function savePrefsToStorage(prefs: CanvasPrefs): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(PREFS_STORAGE_KEY, JSON.stringify(prefs));
  } catch {
    /* private browsing, quota — silently ignore */
  }
}

/** Single source of truth for "what identifies this dim's PK column?"
 *  Three consumers read this (Attributes pane KEY badge, Tree KEY badge,
 *  Inspector Select display + the "PK missing" alert on the Dimensions
 *  pane row) — if they read `dim.primaryKey` or `dim.foreignKey`
 *  directly they disagree on dims that only carry one of the two (M3
 *  imports very often leave `primaryKey` unset because Mondrian 3's
 *  `<Dimension foreignKey="X">` alone is enough for the engine — the
 *  dim-side column is inferred from column-name match).  Route every
 *  consumer through this helper and the badge / Inspector / alert stay
 *  in sync. */
export function dimKeyIdentity(
  dim: Pick<SchemaCanvasDimension, "primaryKey" | "foreignKey">,
): string | null {
  return dim.primaryKey ?? dim.foreignKey ?? null;
}

/** Which on-canvas table the dim's PK column lives on.  Prefers the
 *  explicit `primaryKeyTableId` (set when the PK is on a snowflake
 *  child of the source table), falls back to `sourceTableId`. */
export function dimKeyTableId(
  dim: Pick<SchemaCanvasDimension, "primaryKeyTableId" | "sourceTableId">,
): string | undefined {
  return dim.primaryKeyTableId ?? dim.sourceTableId;
}

/** Resolve the key back to a SPECIFIC attribute on the dim so the KEY
 *  badge, the Inspector display, and the alert all point to the same
 *  row.  Rules:
 *   1. Exact match on logical identity (`attr.name ?? attr.columnName`).
 *   2. Fallback for M3 imports where `dim.primaryKey` still holds a raw
 *      column name: match by `attr.columnName` ONLY when exactly one
 *      attribute has that column (avoids double-badging Product where
 *      two attributes share `columnName='product_id'`).
 *   3. Otherwise return null — the schema is ambiguous, callers should
 *      surface the alert and prompt the user to pick.
 *
 *  Returns the resolved attribute plus a `resolution` tag so callers
 *  can display "resolved by column-name fallback — pick to confirm"
 *  affordances when it's not a clean logical match.
 */
export function resolveKeyAttribute(
  dim: Pick<SchemaCanvasDimension, "primaryKey" | "foreignKey" | "attributes">,
): {
  attr: NonNullable<SchemaCanvasDimension["attributes"]>[number];
  resolution: "logical" | "column-fallback";
} | null {
  const key = dimKeyIdentity(dim);
  if (!key) return null;
  const attrs = dim.attributes ?? [];
  const byLogical = attrs.find((a) => (a.name ?? a.columnName) === key);
  if (byLogical) return { attr: byLogical, resolution: "logical" };
  const byColumn = attrs.filter((a) => a.columnName === key);
  if (byColumn.length === 1)
    return { attr: byColumn[0], resolution: "column-fallback" };
  return null;
}

function makeId(prefix: string): string {
  // Local-only ids — collision-safe across a single user-session, not
  // global. Server persistence will reissue if needed.
  return `${prefix}_${Math.random().toString(36).slice(2, 10)}`;
}

/** Pick a name not present in `existing`.  If `desired` collides, suffix
 *  with `(2)`, `(3)`, … until clear.  Used to keep dim names unique in
 *  the cube and hierarchy names unique within a dim — set-theoretic
 *  cleanliness (two dims named "Customer" would be the same element). */
function ensureUnique(desired: string, existing: string[]): string {
  const set = new Set(existing);
  if (!set.has(desired)) return desired;
  for (let n = 2; n < 10_000; n++) {
    const candidate = `${desired} (${n})`;
    if (!set.has(candidate)) return candidate;
  }
  return `${desired} (${Date.now()})`;
}

function blankState(connectionId: string): SchemaCanvasState {
  return {
    version: 1,
    connectionId,
    label: "",
    tables: [],
    joins: [],
    groups: [],
    dimensions: [],
    measures: [],
    cubes: [],
    updatedAt: new Date(0).toISOString(),
  };
}

/**
 * Read the workbench's former localStorage cube model
 * (`saiku.workbench.cubesState`) and map it to the trimmed
 * `SchemaCanvasCube[]` doc shape — dropping every UI-only flag (edit modes,
 * confirm flags, selection cursors, seq counters).  Used once on load to
 * migrate any cube authored before the model was lifted into the doc.
 * Guarded + never throws — returns `[]` on any problem.
 */
function migrateLegacyCubes(): SchemaCanvasCube[] {
  if (typeof localStorage === "undefined") return [];
  try {
    const raw = localStorage.getItem(LEGACY_CUBES_STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as {
      cubes?: Array<{
        id?: string;
        name?: string;
        measureGroups?: Array<{
          id?: string;
          name?: string;
          measureColumns?: string[];
          factTableId?: string | null;
          dimensionLinks?: SchemaCanvasDimensionLink[];
          measureCaptions?: Record<string, string>;
        }>;
        factsCalcs?: SchemaCanvasCalc[];
        timeCalcs?: SchemaCanvasTimeCalc[];
      }>;
    };
    if (!Array.isArray(parsed.cubes) || parsed.cubes.length === 0) return [];
    return parsed.cubes.map((c) => ({
      id: c.id ?? makeId("cube"),
      name: c.name ?? "Cube",
      measureGroups: (c.measureGroups ?? []).map((g) => ({
        id: g.id ?? makeId("mg"),
        name: g.name ?? "Group",
        measureColumns: Array.isArray(g.measureColumns) ? g.measureColumns : [],
        factTableId: g.factTableId ?? null,
        dimensionLinks: Array.isArray(g.dimensionLinks) ? g.dimensionLinks : [],
        measureCaptions:
          g.measureCaptions && typeof g.measureCaptions === "object"
            ? g.measureCaptions
            : {},
      })),
      calcs: Array.isArray(c.factsCalcs) ? c.factsCalcs : [],
      timeCalcs: Array.isArray(c.timeCalcs) ? c.timeCalcs : undefined,
    }));
  } catch {
    return [];
  }
}

function loadFromStorage(connectionId: string): SchemaCanvasState {
  if (typeof window === "undefined") return blankState(connectionId);
  try {
    const raw = window.localStorage.getItem(STORAGE_PREFIX + connectionId);
    if (!raw) return blankState(connectionId);
    const parsed = JSON.parse(raw) as SchemaCanvasState;
    if (parsed.version !== 1) return blankState(connectionId);
    // Self-heal: dedupe by the EXACT (source_col, target_col) tuple
    // so role-playing dims (multiple distinct joins between the
    // same table pair) survive a reload. Earlier versions of this
    // store enforced one-per-pair; that rule has since been
    // relaxed (see addJoin).
    const tupleMap = new Map<string, SchemaCanvasState["joins"][number]>();
    for (const j of parsed.joins ?? []) {
      const key = [
        [j.sourceTableId, j.sourceColumnName].join("."),
        [j.targetTableId, j.targetColumnName].join("."),
      ]
        .sort()
        .join("::");
      tupleMap.set(key, j);
    }
    parsed.joins = [...tupleMap.values()];
    // Back-fill workbench fields for docs persisted before the
    // Dimensions Workbench landed.  Doc version is NOT bumped — the
    // `?? []` defaults keep old docs round-tripping cleanly.
    if (!Array.isArray(parsed.dimensions)) parsed.dimensions = [];
    if (!Array.isArray(parsed.measures)) parsed.measures = [];
    // Cubes were formerly held in the workbench's own localStorage key.
    // Back-fill an empty array for docs saved before the model moved
    // into the doc, and one-time migrate any legacy workbench cube state
    // so the workbench + exporter see it immediately.
    if (!Array.isArray(parsed.cubes) || parsed.cubes.length === 0) {
      parsed.cubes = migrateLegacyCubes();
    }
    // Migrate old docs that only carried `primaryKeyTableId` (which
    // was overloaded to mean both source-binding AND PK-column-table).
    // Split-forward: source-binding lives on `sourceTableId`; the PK
    // column table stays on `primaryKeyTableId` only for snowflakes.
    for (const d of parsed.dimensions) {
      if (!d.sourceTableId && d.primaryKeyTableId) {
        d.sourceTableId = d.primaryKeyTableId;
      }
    }
    return parsed;
  } catch {
    return blankState(connectionId);
  }
}

function saveToStorage(state: SchemaCanvasState): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(
      STORAGE_PREFIX + state.connectionId,
      JSON.stringify(state),
    );
  } catch {
    /* private browsing, quota — silently ignore */
  }
}

/**
 * Pathfinder UX state — a column-to-column join-path finder anchored at
 * the bottom of the joins panel.  The user clicks "Start" to arm the
 * start slot, clicks a column on the canvas to set it, repeats for
 * "End", then hits "Find path".  Pathfinder searches the join graph
 * (existing joins first; falling back to virtual edges where two on-
 * canvas tables share a column name) and returns the shortest chain.
 * "Create path joins" then wires up any virtual edges along the way.
 */
export interface PathfinderEndpoint {
  tableId: string;
  columnName: string;
}

export interface PathfinderStep {
  fromTableId: string;
  fromColumnName: string;
  toTableId: string;
  toColumnName: string;
  /** Existing join id when the step traverses an already-wired join;
   *  null when it's a name-collision virtual edge the user would still
   *  need to commit via Create path joins. */
  existingJoinId: string | null;
}

export interface PathfinderResult {
  steps: PathfinderStep[];
  hasGaps: boolean;
}

export class SchemaCanvasStore {
  // Reactive ($state): consumers read `store.connectionId` in reactive
  // template positions (e.g. AskDimSumWidget's Send-button gate). A plain
  // field wouldn't re-run those bindings when `switchConnection` reassigns
  // it, so a fresh-canvas → pick-a-connection flow left the DimSum Send
  // button stuck disabled ("Pick a connection first").
  connectionId = $state<string>("");

  // Document — top-level reactive state.
  doc = $state<SchemaCanvasState>(blankState(""));

  /**
   * One-step undo stash.  When set, the toolbar Undo button becomes
   * live and `undo()` swaps this back into `doc`.  Populated
   * automatically by `loadDoc` before every replace (so AI Draft is
   * always reversible) and can be populated by other big operations
   * that want to be undoable.
   */
  previousDoc = $state<SchemaCanvasState | null>(null);

  /**
   * Bumped whenever an external caller (DimSum) writes to `doc.cubes` and
   * wants the Facts & Measures workbench to re-hydrate.  The canvas page
   * watches this and force-remounts `<WorkbenchView>` (which re-reads
   * `doc.cubes` on mount) so the change is visible.  Pure signal — not
   * persisted.
   */
  workbenchReloadNonce = $state<number>(0);

  /**
   * The cube currently selected in the Facts & Measures workbench. Lifted to
   * the store so (a) the selection survives the DimSum-triggered remount and
   * (b) the DimSum chat (which lives in SchemaCanvasView) can reset itself
   * when the user switches to a different cube. Pure UI signal — not persisted.
   */
  activeCubeId = $state<string | null>(null);

  /**
   * Bumped when a saved schema is opened (or duplicated) from the library so
   * the DimSum chat (SchemaCanvasView) starts a fresh conversation for it,
   * instead of restoring the connection's last chat. Pure signal — not persisted.
   */
  chatResetNonce = $state<number>(0);

  // UI state.
  selectedTableId = $state<string | null>(null);
  selectedJoinId = $state<string | null>(null);
  /** Filter expression for the sidebar table catalog. */
  sidebarQuery = $state("");
  /** Source-table catalog from /api/inference/profile-connection. */
  sourceTables = $state<SourceTableCandidate[]>([]);
  /** Selected schema filter — null means "all schemas". When set, the
   *  sidebar list is filtered to this schema. */
  selectedSchema = $state<string | null>(null);
  sourceLoading = $state(false);
  sourceError = $state<string | null>(null);
  /** Which top-level surface the schema designer renders.
   *  - `canvas`     — schema canvas (tables + joins).
   *  - `workbench`  — dimensions / attributes / hierarchies + inspector.
   *  - `facts`      — measure groups + calculated measures + inspector.
   *  - `validate`   — cube validation (sample data / try query / linked cubes).
   *
   *  Legacy `'binary'` value is migrated to `'workbench'` on load. */
  mode = $state<CanvasMode>("canvas");
  /**
   * Globally-highlighted column name. When set, every table on the
   * canvas that contains a column with this name renders with a glow
   * — visual signal of potential join targets for what the user just
   * clicked. Also drives the sidebar search filter.
   */
  highlightedColumn = $state<string | null>(null);
  /** Which table the highlighted column came from — lets the sidebar
   *  footer chip target "link similar on canvas" against THIS column
   *  specifically (origin → every other on-canvas table with the same
   *  column name). */
  highlightOriginTableId = $state<string | null>(null);
  /** What drove the current highlight — a column click or the sidebar
   *  search box. Lets the view's search→highlight effect decide
   *  whether emptying the search should clear the highlight (only when
   *  search drove it; otherwise a click-initiated highlight survives a
   *  manual search clear). */
  highlightSource = $state<"click" | "search" | null>(null);
  /**
   * Click-to-join armed source. When a user clicks a column on table A,
   * we stash it here; the next click on a column on table B commits a
   * join between the two. Clicking the same (table, column) again
   * cancels; clicking another column on the SAME table replaces the
   * pending source (you can't join a table to itself in this model).
   *
   * This is pure UI state — NOT persisted in the doc / localStorage.
   */
  pendingJoinSource = $state<{ tableId: string; columnName: string } | null>(
    null,
  );
  /**
   * Pick mode — toggled by tapping E (no hold required). When ON,
   * column clicks accumulate into `eShiftPicks` instead of arming a
   * single click-to-join.  Commit happens via the picks-panel
   * "Match" button, which takes a group name and wires picks in a
   * STAR pattern (first pick = origin, joins to each subsequent
   * pick).  Lets a user group columns with DIFFERENT names
   * (`product_id` ↔ `prod_id` ↔ `pid`) under a single semantic
   * label like "Product Key". UI state; not persisted.
   */
  pickModeActive = $state<boolean>(false);
  eShiftPicks = $state<Array<{ tableId: string; columnName: string }>>([]);
  /** Bump-counter feedback channel for the join-creation toast.  Any
   *  path that materialises a new join writes here; the view listens
   *  on the `id` and renders a toast.  A single addJoin sets a
   *  descriptive per-join message; batch paths (commitEShiftPicks,
   *  linkSimilarOnCanvas) overwrite with a summary after the loop so
   *  the user gets one toast, not N. */
  lastJoinFeedback = $state<{ id: number; message: string } | null>(null);
  private joinFeedbackCounter = 0;
  /** Pathfinder UX state — see PathfinderEndpoint / PathfinderResult.
   *  When `pathfinderArmed` is non-null, the next column click on the
   *  canvas writes into that slot (start / end) instead of running the
   *  normal click-to-join flow.  Result is computed on-demand via
   *  `findPath()` and surfaced as `pathfinderResult`. */
  pathfinderArmed = $state<"start" | "end" | null>(null);
  pathfinderStart = $state<PathfinderEndpoint | null>(null);
  pathfinderEnd = $state<PathfinderEndpoint | null>(null);
  pathfinderResult = $state<PathfinderResult | null>(null);
  /** Sidebar width in px — user-draggable via the splitter handle. */
  sidebarWidth = $state(288);
  /** Joins panel (right rail) width in px — same splitter pattern.
   *  Clamped at 220-560 in the drag handler. */
  joinsPanelWidth = $state(280);
  /** User's preferred Arrange layout — what the main Arrange button
   *  applies when clicked without picking from the menu.  All
   *  layouts stay individually clickable in the dropdown; this just
   *  controls the one-click default.  Persisted to localStorage as
   *  a per-user preference (NOT per-connection) so the choice
   *  follows the user across cubes and connection switches. */
  defaultLayoutMode = $state<LayoutMode>("star");
  /** Multi-select state for the sidebar table list. Clicking a row
   *  replaces selection; Shift/Cmd-clicking toggles. Dragging any
   *  selected row drags the WHOLE selection at once. Identities are
   *  the same `schema.name` (or bare `name`) form the sidebar uses. */
  sidebarSelectedIdentities = $state<Set<string>>(new Set());
  /**
   * Default columns shown per table when the user hasn't individually
   * collapsed/expanded that table. 0 = headers only. Any positive
   * number caps the visible row count; tables with fewer columns just
   * show all of theirs. Per-table `collapsed` boolean (true = headers
   * only, false = show all) overrides this when set.
   */
  defaultColumnsShown = $state<number>(8);
  /**
   * When ON, dropping a new table onto the canvas silently commits
   * every high-confidence column-name match between the dropped table
   * and the fact. Friendly when starting from a blank canvas; gets in
   * the way when you're trying to test interactions (E, click-to-join)
   * because joins materialise before you can do anything. Default OFF.
   */
  autoSuggestOnDrop = $state<boolean>(false);
  /**
   * When ON, focusing a table or join-group ALSO highlights the actual
   * column rows that participate in the focused joins (green row +
   * auto-expand the table to show them). When OFF (default), focus
   * just dims unrelated stuff — the user follows the lines themselves.
   * Some folks want the green highlight; others prefer the cleaner
   * "just follow the lines" view.
   */
  highlightFocusedColumns = $state<boolean>(false);
  /** When ON, hides every join line + label so the user can see the
   *  bare table layout without the visual noise. Doesn't delete the
   *  joins — just visually hides them. UI-only state. */
  joinsHidden = $state<boolean>(false);
  /** When ON, also render `cube-link` / `inferred-fk` joins (the ones
   *  auto-inferred from Mondrian-4 MeasureGroup `<ForeignKeyLink>` —
   *  semantic, not physical).  Default OFF — canvas shows only
   *  `origin: 'physical'` joins, matching the PhysicalSchema in the
   *  source XML.  UI-only state. */
  showCubeLinks = $state<boolean>(false);
  /**
   * Join-line rendering style.
   *   - `bezier`     — smooth curves (default).
   *   - `smoothstep` — orthogonal segments with rounded corners.
   *   - `step`       — sharp right-angle segments.
   * UI-only; not persisted in the doc (it's a personal preference).
   */
  edgeStyle = $state<"bezier" | "smoothstep" | "step">("smoothstep");
  /**
   * Focus dimming. When the user picks a target — a join group label
   * in the panel, a table on the canvas, etc. — everything else
   * drops opacity so the chosen thing stands out. Click the empty
   * canvas to clear. UI-only.
   */
  focusKind = $state<"none" | "group" | "table">("none");
  focusKey = $state<string | null>(null);
  /** Cursor for binary view: index into the non-fact tables list. */
  binaryCursor = $state(0);
  /** Sidebar → canvas jump request. Set by the sidebar's "on canvas"
   *  result link; consumed by a tiny JumpHandler inside the SvelteFlow
   *  tree which calls `useSvelteFlow().setCenter()` (the hook only
   *  works inside the flow component subtree). `ts` makes repeat
   *  clicks on the same row re-fire the effect. */
  requestedJumpTarget = $state<{
    tableId: string;
    columnName?: string;
    ts: number;
  } | null>(null);
  /** Imperative viewport actions DimSum (or other UI) can request. The
   *  CanvasActionHandler child inside <SvelteFlow> consumes this and
   *  calls the corresponding useSvelteFlow() method. `ts` re-fires
   *  repeat requests of the same kind. Keeps the AI-visible action
   *  registry decoupled from where the imperative API is available. */
  requestedCanvasAction = $state<{
    kind: "zoom_in" | "zoom_out" | "fit_view" | "zoom_to_100" | "center_view";
    ts: number;
  } | null>(null);

  /**
   * Screen→flow coordinate converter, published by the in-flow JumpHandler
   * (which can call `useSvelteFlow()`) so the pane drop handler — mounted
   * OUTSIDE the SvelteFlow provider — can map a drop's viewport coords into
   * flow space (saiku#1634 #3). Null until the flow mounts / in SSR + tests;
   * consumers fall back to pane-relative pixels. Plain field (not `$state`):
   * only read imperatively inside the drop event handler, never in a reactive
   * position, so it needs no reactivity (and functions don't proxy cleanly).
   */
  screenToFlowPosition:
    | ((screen: { x: number; y: number }) => { x: number; y: number })
    | null = null;
  /**
   * Workbench working set — table IDs the user has pulled into the
   * Workbench view for focused side-by-side work. UI-only; not
   * persisted in the doc. Empty means "Workbench shows nothing yet —
   * drag tables in from the sidebar." Removing a table from this set
   * does NOT remove it from the canvas (the canvas is the source of
   * truth; the workbench is a scratch surface).
   */
  workbenchWorkingSet = $state<Set<string>>(new Set());
  /** Soft-threshold warning state — flips true above 30 tables. */
  get thresholdSoft(): boolean {
    return this.doc.tables.length >= 30;
  }

  /**
   * The "active set" — phase-2's unifying primitive for column
   * selection. Two prior pieces of UI (highlightedColumn ↔ across-table
   * name match, and eShiftPicks ↔ explicit E-hold picks) reduce to
   * ONE concept the rest of the canvas can read uniformly: an ordered
   * list of `{tableId, columnName}` plus a mode tag.
   *
   * Modes:
   *  - `pick`  — explicit picks accumulated during E-hold (or via the
   *              joins-panel "picks" affordance later). Survives until
   *              committed or explicitly cleared.
   *  - `auto`  — derived from `highlightedColumn`: every column on
   *              canvas whose name matches.
   *  - `none`  — neither mode active.
   *
   * Both visual surfaces (canvas column rows + Workbench panel) read
   * this and apply the SAME green-glow vocabulary, replacing the
   * Workbench's old red-ring treatment.
   */
  get activeSetMode(): "auto" | "pick" | "none" {
    if (this.eShiftPicks.length > 0) return "pick";
    if (this.highlightedColumn !== null) return "auto";
    return "none";
  }

  get activeSet(): Array<{ tableId: string; columnName: string }> {
    if (this.eShiftPicks.length > 0) return this.eShiftPicks;
    if (this.highlightedColumn !== null) {
      const colName = this.highlightedColumn;
      const out: Array<{ tableId: string; columnName: string }> = [];
      for (const t of this.doc.tables) {
        if (t.columns.some((c) => c.name === colName)) {
          out.push({ tableId: t.id, columnName: colName });
        }
      }
      return out;
    }
    return [];
  }

  /**
   * `${tableId}:${columnName}` keys for fast O(1) membership tests in
   * render hot paths — TableNode column rows test against this Set to
   * decide whether to apply the green glow.
   */
  get activeSetKeys(): Set<string> {
    const out = new Set<string>();
    for (const p of this.activeSet) {
      out.add(`${p.tableId}:${p.columnName}`);
    }
    return out;
  }

  constructor(connectionId: string) {
    this.connectionId = connectionId;
    this.doc = loadFromStorage(connectionId);
    // One-time migration: strip the old "implicit-default hierarchy"
    // auto-seed that the previous addDimension code created.  Match
    // criterion: hier.name === dim.name AND hier.levels.length === 0
    // — those are the obvious leftovers from the convention we
    // abandoned (hierarchies are always explicit now).  Won't strip
    // a hier the user named the same as the dim and populated.
    for (const d of this.doc.dimensions ?? []) {
      d.hierarchies = d.hierarchies.filter(
        (h) => !(h.levels.length === 0 && h.name === d.name),
      );
      // Sibling-unique hierarchy names per dim.  Pre-uniqueness
      // data may have shipped duplicates; resolve by suffixing
      // "(2)", "(3)", ….  Keep the first occurrence's name intact.
      const seen = new Set<string>();
      for (const h of d.hierarchies) {
        if (seen.has(h.name)) {
          let n = 2;
          while (seen.has(`${h.name} (${n})`)) n++;
          h.name = `${h.name} (${n})`;
        }
        seen.add(h.name);
      }
    }
    // Same for dim names at the cube level.
    const seenDimNames = new Set<string>();
    for (const d of this.doc.dimensions ?? []) {
      if (seenDimNames.has(d.name)) {
        let n = 2;
        while (seenDimNames.has(`${d.name} (${n})`)) n++;
        d.name = `${d.name} (${n})`;
      }
      seenDimNames.add(d.name);
    }
    // Restore per-user UI preferences (NOT per-connection).
    const prefs = loadPrefsFromStorage();
    if (prefs.defaultLayoutMode) {
      this.defaultLayoutMode = prefs.defaultLayoutMode;
    }
    const migrated = migrateMode(prefs.mode);
    if (migrated) this.mode = migrated;
  }

  /** Update the default Arrange layout and persist it as a per-user
   *  preference.  Callers should use this method rather than mutating
   *  `defaultLayoutMode` directly so the localStorage write stays in
   *  one place. */
  setDefaultLayoutMode(mode: LayoutMode): void {
    this.defaultLayoutMode = mode;
    const prefs = loadPrefsFromStorage();
    prefs.defaultLayoutMode = mode;
    savePrefsToStorage(prefs);
  }

  /** Set the active view tab and persist as a per-user preference so the
   *  next session lands on the same tab.  Tab toggle UI should call
   *  this rather than mutating `mode` directly. */
  setMode(mode: CanvasMode): void {
    this.mode = mode;
    const prefs = loadPrefsFromStorage();
    prefs.mode = mode;
    savePrefsToStorage(prefs);
  }

  /**
   * Swap the active connection without recreating the store. Persists
   * the current doc, loads the doc for the new connection from
   * localStorage, and resets the UI state (selection, mode cursor).
   * Source-table catalog is the caller's responsibility — the
   * /cubes/new/canvas page kicks off a fresh fetch on connection
   * change.
   */
  switchConnection(connectionId: string): void {
    if (this.connectionId === connectionId) return;
    // Persist whatever's currently in flight under the old key.
    saveToStorage(this.doc);
    this.connectionId = connectionId;
    this.doc = loadFromStorage(connectionId);
    this.selectedTableId = null;
    this.selectedJoinId = null;
    this.binaryCursor = 0;
    this.sourceTables = [];
    this.sourceLoading = false;
    this.sourceError = null;
    this.sidebarQuery = "";
  }

  private touch(): void {
    this.doc.updatedAt = new Date().toISOString();
    saveToStorage(this.doc);
  }

  /** Public setter for the cube label.  Goes through the same
   *  touch() → saveToStorage path as canvas mutations, so renames
   *  survive a reload.  The two-way `bind:value` on the title input
   *  used to mutate `store.doc.label` directly, which never wrote
   *  to localStorage. */
  setLabel(label: string): void {
    this.doc.label = label;
    this.touch();
  }

  /** Add a table from the source catalog onto the canvas at the given position. */
  addTable(
    candidate: SourceTableCandidate,
    position: { x: number; y: number },
  ): SchemaCanvasTable {
    this.maybeSnapshotForUndo();
    // Dedupe (#1084) — a physical table (schema+name) may only appear
    // once on the canvas. Every add path (drag-drop, "+ Add to canvas",
    // DimSum's add_table_to_canvas tool) funnels through here, so this
    // is the single guard against a second node with a fresh id being
    // pushed for a table that's already present. If found, return the
    // existing node untouched rather than creating a duplicate.
    const identity = candidate.schema
      ? `${candidate.schema}.${candidate.name}`
      : candidate.name;
    const existing = this.doc.tables.find(
      (t) => (t.schema ? `${t.schema}.${t.name}` : t.name) === identity,
    );
    if (existing) return existing;
    // Peer model — every table on the canvas is equal. The `role`
    // field stays on the data model (Mondrian export still needs a
    // fact at the END), but the canvas itself doesn't designate one
    // any more. New tables come in as plain dimensions.
    const role: SchemaCanvasTableRole = "dimension";
    const table: SchemaCanvasTable = {
      id: makeId("tbl"),
      schema: candidate.schema,
      name: candidate.name,
      role,
      columns: candidate.columns.map((c) => ({
        name: c.name,
        sqlType: c.sqlType,
      })),
      position,
      groupId: null,
    };
    this.doc.tables.push(table);
    this.touch();
    return table;
  }

  removeTable(tableId: string): void {
    this.maybeSnapshotForUndo();
    this.doc.tables = this.doc.tables.filter((t) => t.id !== tableId);
    // Cascade: drop any joins that referenced this table.
    this.doc.joins = this.doc.joins.filter(
      (j) => j.sourceTableId !== tableId && j.targetTableId !== tableId,
    );
    if (this.selectedTableId === tableId) this.selectedTableId = null;
    this.touch();
  }

  moveTable(tableId: string, position: { x: number; y: number }): void {
    const t = this.doc.tables.find((t) => t.id === tableId);
    if (!t) return;
    t.position = position;
    this.touch();
  }

  /** Force a single table to show every column (overrides the global
   *  default). Used by the "+ N more" footer affordance — clicking it
   *  is an explicit "I want to see everything in this table". The
   *  chevron still cycles undef ↔ true after that. */
  expandTableFully(tableId: string): void {
    const t = this.doc.tables.find((t) => t.id === tableId);
    if (!t) return;
    t.collapsed = false;
    this.touch();
  }

  toggleTableCollapsed(tableId: string): void {
    const t = this.doc.tables.find((t) => t.id === tableId);
    if (!t) return;
    // Cycle is two states: `undefined` (follow `defaultColumnsShown`)
    // ↔ `true` (header only). The chevron NEVER sets the third state
    // (`false` = force show-all) because that would pin the table
    // past any future change to `defaultColumnsShown` — Amelia hit
    // this and had to click "Reset views" to escape. "Force show all"
    // is reachable only via the explicit "Expand all" toolbar button.
    if (t.collapsed === true) {
      t.collapsed = undefined;
    } else {
      t.collapsed = true;
    }
    this.touch();
  }

  /** Reset every table's per-table collapse override so they all
   *  follow the global `defaultColumnsShown` setting again. */
  resetCollapseOverrides(): void {
    for (const t of this.doc.tables) t.collapsed = undefined;
    this.touch();
  }

  /** Force every table to show all its columns (overrides the global
   *  default). Companion to the toolbar's "Expand all" button. */
  expandAllTables(): void {
    for (const t of this.doc.tables) t.collapsed = false;
    this.touch();
  }

  /** Force every table to collapse to header-only. */
  collapseAllTables(): void {
    for (const t of this.doc.tables) t.collapsed = true;
    this.touch();
  }

  /** Add a canvas table to the workbench working set. No-op if
   *  already present. Doesn't touch the canvas. */
  addToWorkbench(tableId: string): void {
    if (this.workbenchWorkingSet.has(tableId)) return;
    const next = new Set(this.workbenchWorkingSet);
    next.add(tableId);
    this.workbenchWorkingSet = next;
  }

  /** Remove a table from the workbench WITHOUT removing it from the
   *  canvas. */
  removeFromWorkbench(tableId: string): void {
    if (!this.workbenchWorkingSet.has(tableId)) return;
    const next = new Set(this.workbenchWorkingSet);
    next.delete(tableId);
    this.workbenchWorkingSet = next;
  }

  clearWorkbench(): void {
    this.workbenchWorkingSet = new Set();
  }

  /** The actual tables in the workbench's working set (canvas tables
   *  filtered by membership). The Set may include stale IDs from a
   *  prior reload; we resolve against the live canvas tables. */
  get workbenchTables(): SchemaCanvasTable[] {
    return this.doc.tables.filter((t) => this.workbenchWorkingSet.has(t.id));
  }

  /** `schema.name` identities of every table currently in the
   *  workbench's working set — used by the Workbench's TableSidebar
   *  to grey out tables already in focus (vs. all canvas tables). */
  get workbenchTableIdentities(): Set<string> {
    return new Set(
      this.workbenchTables.map((t) =>
        t.schema ? `${t.schema}.${t.name}` : t.name,
      ),
    );
  }

  setTableRole(tableId: string, role: SchemaCanvasTableRole): void {
    const t = this.doc.tables.find((t) => t.id === tableId);
    if (!t) return;
    // Only one fact at a time — demote any prior fact to dimension when
    // another is promoted.
    if (role === "fact") {
      for (const other of this.doc.tables) {
        if (other.id !== tableId && other.role === "fact")
          other.role = "dimension";
      }
    }
    t.role = role;
    this.touch();
  }

  addJoin(j: Omit<SchemaCanvasJoin, "id">): SchemaCanvasJoin {
    this.maybeSnapshotForUndo();
    // User-initiated joins default to `physical` — only the importer
    // creates `cube-link` / `inferred-fk` joins, and it sets origin
    // explicitly when it does.
    const join: SchemaCanvasJoin = {
      ...j,
      id: makeId("join"),
      origin: j.origin ?? "physical",
    };
    // Multiple joins per table pair are valid for role-playing
    // dimensions: a fact joining `date` three times via order_date_id,
    // ship_date_id, and invoice_date_id is one classic shape. Tom's
    // "no composite keys" rule meant a SINGLE join using multiple
    // columns simultaneously — different from N distinct
    // single-column joins between the same pair. We only dedupe on
    // the exact (source_col, target_col) tuple so the same join
    // can't be drawn twice.
    const existingIdx = this.doc.joins.findIndex((existing) => {
      const sameDirection =
        existing.sourceTableId === join.sourceTableId &&
        existing.targetTableId === join.targetTableId &&
        existing.sourceColumnName === join.sourceColumnName &&
        existing.targetColumnName === join.targetColumnName;
      const swappedDirection =
        existing.sourceTableId === join.targetTableId &&
        existing.targetTableId === join.sourceTableId &&
        existing.sourceColumnName === join.targetColumnName &&
        existing.targetColumnName === join.sourceColumnName;
      return sameDirection || swappedDirection;
    });
    if (existingIdx >= 0) {
      this.doc.joins[existingIdx] = join;
      this.touch();
      return join;
    }
    // Within a given table pair, a single (source_table, source_column)
    // endpoint can only participate in ONE join. If the same source
    // endpoint is being paired with a DIFFERENT target column on the
    // same target table, that's ambiguous (a "first_name" endpoint
    // wired to both `fname` and `lname`) so replace the older join
    // instead of accumulating semantically-inconsistent pairs.
    // Role-playing (same target column reused via DIFFERENT source
    // columns) stays legal because the check keys off the source
    // endpoint, not the target.
    const ambiguousIdx = this.doc.joins.findIndex((existing) => {
      const sameDirection =
        existing.sourceTableId === join.sourceTableId &&
        existing.targetTableId === join.targetTableId &&
        existing.sourceColumnName === join.sourceColumnName &&
        existing.targetColumnName !== join.targetColumnName;
      // Swapped: the incoming join's TARGET endpoint (table + column)
      // matches the existing join's SOURCE endpoint — same conceptual
      // "source" endpoint of the pair, written in reverse. Other-side
      // columns must still differ, else the sameDirection dedupe (or
      // exact-tuple dedupe above) would have caught it.
      const swappedDirection =
        existing.sourceTableId === join.targetTableId &&
        existing.targetTableId === join.sourceTableId &&
        existing.sourceColumnName === join.targetColumnName &&
        existing.targetColumnName !== join.sourceColumnName;
      return sameDirection || swappedDirection;
    });
    if (ambiguousIdx >= 0) {
      const prior = this.doc.joins[ambiguousIdx];
      // Both detection cases converge on: prior.sourceColumnName is
      // the endpoint being reused, prior.targetColumnName is what it
      // was previously paired with. (In sameDirection the incoming's
      // source matches prior.sourceColumnName; in swappedDirection
      // the incoming's target does. Either way, prior tells us the
      // old story.)
      const reusedColumn = prior.sourceColumnName;
      const oldPairedColumn = prior.targetColumnName;
      this.doc.joins[ambiguousIdx] = join;
      this.touch();
      this.pushJoinFeedback(
        `Replaced join (${reusedColumn} was already paired with ${oldPairedColumn})`,
      );
      return join;
    }
    this.doc.joins.push(join);
    this.touch();
    this.pushJoinFeedback(this.describeJoinCreation(join));
    return join;
  }

  private describeJoinCreation(j: SchemaCanvasJoin): string {
    const src = this.doc.tables.find((t) => t.id === j.sourceTableId);
    const tgt = this.doc.tables.find((t) => t.id === j.targetTableId);
    const srcName = src?.name ?? "?";
    const tgtName = tgt?.name ?? "?";
    return `Joined ${srcName}.${j.sourceColumnName} ↔ ${tgtName}.${j.targetColumnName}`;
  }

  private pushJoinFeedback(message: string): void {
    this.lastJoinFeedback = { id: ++this.joinFeedbackCounter, message };
  }

  removeJoin(joinId: string): void {
    this.maybeSnapshotForUndo();
    const target = this.doc.joins.find((j) => j.id === joinId);
    // Cube-link + inferred-fk joins are owned by the cube-side
    // MeasureGroup ForeignKeyLink, not by the canvas.  The user must
    // remove the FK link from the MG's dim-link checklist to make it
    // go away; deleting it on the canvas would just have the importer
    // recreate it.  Silently no-op so accidental clicks don't surprise.
    if (
      target &&
      (target.origin === "cube-link" || target.origin === "inferred-fk")
    ) {
      return;
    }
    this.doc.joins = this.doc.joins.filter((j) => j.id !== joinId);
    if (this.selectedJoinId === joinId) this.selectedJoinId = null;
    // If the removed join was the LAST one for its canonical key,
    // clear the rename so a future re-creation doesn't silently
    // inherit a stale label.  When other joins for the same key
    // still exist (the group survives the removal), the rename
    // stays — same group, same label.
    if (target) {
      const key = SchemaCanvasStore.joinCanonicalKey(target);
      const stillHasKey = this.doc.joins.some(
        (j) => SchemaCanvasStore.joinCanonicalKey(j) === key,
      );
      if (!stillHasKey && this.doc.joinGroupRenames?.[key]) {
        const next = { ...this.doc.joinGroupRenames };
        delete next[key];
        this.doc.joinGroupRenames = next;
      }
    }
    this.touch();
  }

  /** Remove a column from a group while keeping the rest of the
   *  group's equivalence intact.
   *
   *  Three cases:
   *  1. Removing the endpoint leaves fewer than 2 remaining → group
   *     dissolves (delete every join in it).
   *  2. Removing it leaves at least one join that DOESN'T touch
   *     the endpoint → just drop the joins that do.
   *  3. Removing it leaves 2+ endpoints but ZERO surviving joins
   *     (the endpoint was the star anchor) → re-anchor: pick the
   *     first remaining endpoint as the new anchor and rewire a
   *     fresh star to the rest, re-applying the group label.
   *
   *  The UI doesn't see any of this — it just calls
   *  `removeEndpointFromGroup` and the group's semantic "these are
   *  all the same" stays true, no matter which member is removed.
   */
  removeEndpointFromGroup(
    groupLabel: string,
    tableId: string,
    columnName: string,
  ): void {
    const groupJoins = this.doc.joins.filter(
      (j) => this.joinGroupLabelFor(j) === groupLabel,
    );
    if (groupJoins.length === 0) return;
    const touchesEndpoint = (j: SchemaCanvasJoin) =>
      (j.sourceTableId === tableId && j.sourceColumnName === columnName) ||
      (j.targetTableId === tableId && j.targetColumnName === columnName);
    const joinsTouchingEp = groupJoins.filter(touchesEndpoint);
    const remainingJoins = groupJoins.filter((j) => !touchesEndpoint(j));
    // Build the ordered set of endpoints (excluding the one being
    // removed) — preserve the order they first appear so the new
    // anchor is deterministic.
    const seen = new Set<string>();
    const remainingEndpoints: Array<{ tableId: string; columnName: string }> =
      [];
    const skipKey = `${tableId}:${columnName}`;
    for (const j of groupJoins) {
      for (const ep of [
        { tableId: j.sourceTableId, columnName: j.sourceColumnName },
        { tableId: j.targetTableId, columnName: j.targetColumnName },
      ]) {
        const k = `${ep.tableId}:${ep.columnName}`;
        if (k === skipKey || seen.has(k)) continue;
        seen.add(k);
        remainingEndpoints.push(ep);
      }
    }
    // Case 1 — group falls under 2 members → kill it.
    if (remainingEndpoints.length < 2) {
      for (const j of groupJoins) this.removeJoin(j.id);
      return;
    }
    // Case 2 — the endpoint wasn't the anchor; just drop the joins
    // that touch it and leave the rest of the star intact.
    if (remainingJoins.length > 0) {
      for (const j of joinsTouchingEp) this.removeJoin(j.id);
      return;
    }
    // Case 3 — the endpoint was the anchor; rebuild the star
    // using the first remaining endpoint as the new anchor and
    // re-applying the same group label.
    for (const j of groupJoins) this.removeJoin(j.id);
    const newAnchor = remainingEndpoints[0];
    const peers = remainingEndpoints.slice(1);
    const renames = { ...(this.doc.joinGroupRenames ?? {}) };
    for (const peer of peers) {
      if (newAnchor.tableId === peer.tableId) continue;
      const created = this.addJoin({
        sourceTableId: newAnchor.tableId,
        sourceColumnName: newAnchor.columnName,
        targetTableId: peer.tableId,
        targetColumnName: peer.columnName,
        kind: "inner",
      });
      const canonicalKey = SchemaCanvasStore.joinCanonicalKey(created);
      renames[canonicalKey] = groupLabel;
    }
    this.doc.joinGroupRenames = renames;
    this.touch();
  }

  /** Remove every join in `joinIds`. Single pass, single save. */
  removeJoins(joinIds: Iterable<string>): void {
    const set = joinIds instanceof Set ? joinIds : new Set(joinIds);
    if (set.size === 0) return;
    this.maybeSnapshotForUndo();
    this.doc.joins = this.doc.joins.filter((j) => !set.has(j.id));
    if (this.selectedJoinId && set.has(this.selectedJoinId)) {
      this.selectedJoinId = null;
    }
    this.touch();
  }

  focusGroup(label: string): void {
    // `focusKey` for a group is the rendered LABEL (so
    // joinIdsInFocus / tableIdsInFocus can match every join under
    // that label, even when the group spans multiple canonical
    // keys via pick-mode renames).
    if (this.focusKind === "group" && this.focusKey === label) {
      this.clearFocus();
      return;
    }
    this.focusKind = "group";
    this.focusKey = label;
    // Auto-flip the column-highlight toggle ON when a group is
    // focused — clicking a group means "show me the data you
    // grouped," so the row glow + auto-expand are the natural
    // default.  The user is still free to toggle it back off
    // mid-focus if they prefer the cleaner line-only view.
    this.highlightFocusedColumns = true;
    // Drop any stale name-match highlight (from a previous column
    // click or sidebar search) so the only thing lighting up is
    // the group itself — otherwise the user can't tell which
    // columns are "in the group" vs "still glowing from earlier".
    this.highlightedColumn = null;
    this.highlightOriginTableId = null;
    this.highlightSource = null;
  }

  focusTableNode(tableId: string): void {
    if (this.focusKind === "table" && this.focusKey === tableId) {
      this.clearFocus();
      return;
    }
    this.focusKind = "table";
    this.focusKey = tableId;
  }

  clearFocus(): void {
    this.focusKind = "none";
    this.focusKey = null;
  }

  /**
   * Reset every transient interaction state to neutral. Wired to
   * SvelteFlow's pane-click — clicking the empty canvas should clear
   * focus, drop the armed click-to-join, clear the green column
   * highlight, deselect the join, abandon any held E-shift collection,
   * and reset the sidebar search to neutral. The doc itself
   * (tables / joins / groups) is untouched.
   */
  clearAllInteractions(): void {
    this.focusKind = "none";
    this.focusKey = null;
    this.highlightedColumn = null;
    this.highlightOriginTableId = null;
    this.highlightSource = null;
    this.pendingJoinSource = null;
    this.selectedJoinId = null;
    this.selectedTableId = null;
    this.sidebarQuery = "";
    this.pickModeActive = false;
    this.eShiftPicks = [];
    // Also drop any pending jump request — otherwise the
    // JumpHandler's $effect re-fires on the stale target after
    // the rest of state was cleared and re-stamps selectedTableId
    // + highlightedColumn behind the user's back.
    this.requestedJumpTarget = null;
  }

  /** Returns the set of join ids that are IN focus for the current
   *  focus state. Empty set means "no focus active" — callers should
   *  treat that as "everything is in focus". */
  joinIdsInFocus(): Set<string> {
    const out = new Set<string>();
    if (this.focusKind === "group" && this.focusKey !== null) {
      // Group focus filters by LABEL, not canonical key, so a
      // user-renamed group like "Random" (which can span multiple
      // canonical keys when picks crossed different column names)
      // lights up every join in the rendered group — not just the
      // ones whose canonical key happens to match the click target.
      const label = this.focusKey;
      for (const j of this.doc.joins) {
        if (this.joinGroupLabelFor(j) === label) out.add(j.id);
      }
    } else if (this.focusKind === "table" && this.focusKey !== null) {
      const id = this.focusKey;
      for (const j of this.doc.joins) {
        if (j.sourceTableId === id || j.targetTableId === id) out.add(j.id);
      }
    }
    return out;
  }

  /** Returns the set of table ids that are IN focus. */
  tableIdsInFocus(): Set<string> {
    const out = new Set<string>();
    if (this.focusKind === "group" && this.focusKey !== null) {
      // Group focus filters by LABEL — see joinIdsInFocus() for the
      // rationale (multi-canonical-key groups created via pick mode).
      const label = this.focusKey;
      for (const j of this.doc.joins) {
        if (this.joinGroupLabelFor(j) === label) {
          out.add(j.sourceTableId);
          out.add(j.targetTableId);
        }
      }
    } else if (this.focusKind === "table" && this.focusKey !== null) {
      out.add(this.focusKey);
      for (const j of this.doc.joins) {
        if (j.sourceTableId === this.focusKey) out.add(j.targetTableId);
        else if (j.targetTableId === this.focusKey) out.add(j.sourceTableId);
      }
    }
    return out;
  }

  /**
   * Run auto-suggest for one table against every OTHER table on the
   * canvas — commit every high-confidence column-name match as a
   * join. Used by the drop handler AND by the "pull-in matching
   * tables" feature; respects `addJoin`'s tuple-dedupe.
   */
  runAutoSuggestForTable(tableId: string): void {
    const t = this.doc.tables.find((tt) => tt.id === tableId);
    if (!t) return;
    for (const other of this.doc.tables) {
      if (other.id === tableId) continue;
      const suggestions = suggestJoins(t, other).filter(
        (s) => s.confidence === "high",
      );
      for (const s of suggestions) {
        this.addJoin({
          sourceTableId: tableId,
          sourceColumnName: s.sourceColumnName,
          targetTableId: other.id,
          targetColumnName: s.targetColumnName,
          kind: s.kind,
        });
      }
    }
  }

  /**
   * For the currently-highlighted column (or an explicit name),
   * scan the SOURCE catalog for every table containing a column with
   * that name and pull each one not yet on the canvas onto it. If
   * `autoSuggestOnDrop` is ON, every newly-added table is also wired
   * to its peers via runAutoSuggestForTable. Returns the count of
   * tables actually pulled in.
   */
  /**
   * For an on-canvas column, find every OTHER table already on the
   * canvas that has a column with the same (case-insensitive) name
   * and commit a join from this column to each of them. Returns the
   * count of joins actually added (existing-tuple dedupe applies).
   * Companion to `pullInMatchingColumnTables` but scoped to what's
   * already on the canvas.
   */
  linkSimilarOnCanvas(originTableId: string, columnName: string): number {
    const needle = columnName.toLowerCase();
    let added = 0;
    this.withUndoBatch(() => {
      for (const t of this.doc.tables) {
        if (t.id === originTableId) continue;
        const match = t.columns.find((c) => c.name.toLowerCase() === needle);
        if (!match) continue;
        const before = this.doc.joins.length;
        this.addJoin({
          sourceTableId: originTableId,
          sourceColumnName: columnName,
          targetTableId: t.id,
          targetColumnName: match.name,
          kind: "inner",
        });
        if (this.doc.joins.length > before) added++;
      }
    });
    // One summary toast for the batch, overwriting the per-join
    // feedback each addJoin pushed.
    if (added > 1) {
      this.pushJoinFeedback(`${added} joins created (linked ${columnName})`);
    }
    return added;
  }

  pullInMatchingColumnTables(columnName: string): number {
    const needle = columnName.trim().toLowerCase();
    if (!needle) return 0;
    const onCanvas = this.tableIdentitiesOnCanvas;
    const candidates = this.sourceTables.filter((c) => {
      const ident = c.schema ? `${c.schema}.${c.name}` : c.name;
      if (onCanvas.has(ident)) return false;
      return c.columns.some((col) => col.name.toLowerCase() === needle);
    });
    if (candidates.length === 0) return 0;
    // Lay them out in a horizontal row to the right of the rightmost
    // existing table — keeps the new ones together visually.
    const rightmostX = this.doc.tables.reduce(
      (m, t) => Math.max(m, t.position.x),
      0,
    );
    const baseX = rightmostX + 320;
    const baseY = 80;
    const added: string[] = [];
    // One Undo step for the whole pull-in (tables + any auto-joins).
    this.withUndoBatch(() => {
      candidates.forEach((cand, i) => {
        const t = this.addTable(cand, { x: baseX, y: baseY + i * 280 });
        added.push(t.id);
      });
      if (this.autoSuggestOnDrop) {
        for (const id of added) this.runAutoSuggestForTable(id);
      }
    });
    return added.length;
  }

  /** Wipe every join. Tables stay where they are.  Also clears the
   *  group-rename map and every piece of UI state that referenced a
   *  now-deleted join — otherwise recreating the same canonical pair
   *  silently inherits the previous label, and stale selection /
   *  focus / pathfinder state would point at edges that no longer
   *  exist. */
  removeAllJoins(): void {
    if (this.doc.joins.length === 0) return;
    this.doc.joins = [];
    this.doc.joinGroupRenames = {};
    this.selectedJoinId = null;
    this.pendingJoinSource = null;
    this.pickModeActive = false;
    this.eShiftPicks = [];
    this.pathfinderResult = null;
    this.highlightedColumn = null;
    this.highlightOriginTableId = null;
    this.highlightSource = null;
    this.focusKind = "none";
    this.focusKey = null;
    this.touch();
  }

  // ── Dimensions Workbench (Step 2) ────────────────────────────────
  // Mondrian-shaped dimension/hierarchy/level/measure authoring sits
  // on top of the join graph from Step 1.  Persistence rides the
  // same touch() → saveToStorage path as everything else on the doc.

  /** Read accessor with default — older docs in localStorage didn't
   *  carry `dimensions`. */
  get dimensions(): SchemaCanvasDimension[] {
    return this.doc.dimensions ?? [];
  }

  /** Read accessor with default — older docs in localStorage didn't
   *  carry `measures`. */
  get measures(): SchemaCanvasMeasure[] {
    return this.doc.measures ?? [];
  }

  /** Create a new dimension. If a column reference is supplied, the
   *  dimension is seeded with a first hierarchy that already holds
   *  that column as its sole level — matches the drop UX where a
   *  column dragged onto the empty zone materialises a full
   *  dimension in one gesture. */
  addDimension(seed?: {
    name?: string;
    tableId?: string;
    columnName?: string;
    columnType?: SchemaCanvasColumnKind;
  }): SchemaCanvasDimension {
    const tableName =
      seed?.tableId !== undefined
        ? (this.doc.tables.find((t) => t.id === seed.tableId)?.name ?? null)
        : null;
    const desired =
      seed?.name?.trim() || tableName || seed?.columnName || "New dimension";
    const name = ensureUnique(
      desired,
      (this.doc.dimensions ?? []).map((d) => d.name),
    );
    // Default: zero hierarchies.  The user explicitly composes
    // hierarchies in Col 3 (Make hierarchy) or implicitly via the
    // "singleton hierarchy for this attribute" checkbox on the
    // Attributes pane.  Only the column-drop seed path (used by the
    // legacy Cards/Tree drag UX) still auto-creates one hierarchy
    // since the user dropped a specific column.
    const hierarchies: SchemaCanvasHierarchy[] = [];
    if (seed?.tableId && seed?.columnName) {
      hierarchies.push({
        id: makeId("hier"),
        name,
        hasAll: true,
        levels: [
          {
            id: makeId("lvl"),
            name: seed.columnName,
            tableId: seed.tableId,
            columnName: seed.columnName,
            type: seed.columnType,
          },
        ],
      });
    }
    const dim: SchemaCanvasDimension = {
      id: makeId("dim"),
      name,
      hierarchies,
      // Set BOTH: `sourceTableId` is the canonical binding the
      // Workbench (Attrs count, FK selector, attribute picker)
      // reads.  `primaryKeyTableId` kept for the snowflake case +
      // legacy compatibility.  The types.ts split (2026-07-30ish)
      // made `sourceTableId` primary but addDimension wasn't
      // updated — freshly added dims read as "no table bound".
      sourceTableId: seed?.tableId,
      primaryKeyTableId: seed?.tableId,
    };
    this.doc.dimensions = [...this.dimensions, dim];
    this.touch();
    return dim;
  }

  updateDimension(
    dimensionId: string,
    patch: Partial<Omit<SchemaCanvasDimension, "id" | "hierarchies">>,
  ): void {
    const d = this.dimensions.find((d) => d.id === dimensionId);
    if (!d) return;
    Object.assign(d, patch);
    // Note: we used to auto-rename any hierarchy whose name matched
    // the old dim name when the dim was renamed — to keep the
    // Mondrian "implicit-default" convention.  Dropped once the
    // authoring model became "hierarchies are always explicit".
    // Hierarchies are now independent of the dim's label; renaming
    // the dim never touches them.
    this.touch();
  }

  removeDimension(dimensionId: string): void {
    this.doc.dimensions = this.dimensions.filter((d) => d.id !== dimensionId);
    this.touch();
  }

  /** Add a {tableId, columnName} pair to the dim's attribute set A.
   *  No-op if already present.  `attributes === undefined` is the
   *  blank-slate state; the first add materialises it as a one-item
   *  array, matching the "user starts including" mode. */
  addAttribute(dimensionId: string, tableId: string, columnName: string): void {
    const d = this.dimensions.find((d) => d.id === dimensionId);
    if (!d) return;
    const list = d.attributes ?? [];
    if (list.some((a) => a.tableId === tableId && a.columnName === columnName))
      return;
    d.attributes = [...list, { tableId, columnName }];
    this.touch();
  }

  /** Patch one attribute's editable Mondrian-4 fields (name, display /
   *  sort / caption columns, description) — inspector #959. Identified by
   *  its {tableId, columnName} identity, which is NOT itself patched here. */
  updateAttribute(
    dimensionId: string,
    tableId: string,
    columnName: string,
    patch: Partial<
      Omit<
        NonNullable<SchemaCanvasDimension["attributes"]>[number],
        "tableId" | "columnName"
      >
    >,
  ): void {
    const d = this.dimensions.find((d) => d.id === dimensionId);
    const a = d?.attributes?.find(
      (x) => x.tableId === tableId && x.columnName === columnName,
    );
    if (!a) return;
    Object.assign(a, patch);
    this.touch();
  }

  /** Remove a {tableId, columnName} pair from the dim's attribute set
   *  A.  Leaves the array (even empty) in place so the dim is
   *  distinguishably "user has touched A" vs "user hasn't yet". */
  removeAttribute(
    dimensionId: string,
    tableId: string,
    columnName: string,
  ): void {
    const d = this.dimensions.find((d) => d.id === dimensionId);
    if (!d?.attributes) return;
    d.attributes = d.attributes.filter(
      (a) => !(a.tableId === tableId && a.columnName === columnName),
    );
    this.touch();
  }

  /** Replace A wholesale — used by bulk include-all / exclude-all. */
  setAttributes(
    dimensionId: string,
    attributes: { tableId: string; columnName: string }[],
  ): void {
    const d = this.dimensions.find((d) => d.id === dimensionId);
    if (!d) return;
    d.attributes = attributes;
    this.touch();
  }

  addHierarchy(
    dimensionId: string,
    name?: string,
  ): SchemaCanvasHierarchy | null {
    const d = this.dimensions.find((d) => d.id === dimensionId);
    if (!d) return null;
    const desired = name?.trim() || `Hierarchy ${d.hierarchies.length + 1}`;
    const h: SchemaCanvasHierarchy = {
      id: makeId("hier"),
      name: ensureUnique(
        desired,
        d.hierarchies.map((x) => x.name),
      ),
      hasAll: true,
      levels: [],
    };
    d.hierarchies = [...d.hierarchies, h];
    this.touch();
    return h;
  }

  updateHierarchy(
    dimensionId: string,
    hierarchyId: string,
    patch: Partial<Omit<SchemaCanvasHierarchy, "id" | "levels">>,
  ): void {
    const d = this.dimensions.find((d) => d.id === dimensionId);
    const h = d?.hierarchies.find((h) => h.id === hierarchyId);
    if (!h) return;
    Object.assign(h, patch);
    this.touch();
  }

  removeHierarchy(dimensionId: string, hierarchyId: string): void {
    const d = this.dimensions.find((d) => d.id === dimensionId);
    if (!d) return;
    d.hierarchies = d.hierarchies.filter((h) => h.id !== hierarchyId);
    this.touch();
  }

  addLevel(
    dimensionId: string,
    hierarchyId: string,
    level: {
      tableId: string;
      columnName: string;
      name?: string;
      type?: SchemaCanvasColumnKind;
    },
  ): SchemaCanvasLevel | null {
    const d = this.dimensions.find((d) => d.id === dimensionId);
    const h = d?.hierarchies.find((h) => h.id === hierarchyId);
    if (!h) return null;
    // Skip the exact same column landing twice in one hierarchy —
    // stops a stray double-drop from creating an obviously useless
    // duplicate row.  Different hierarchies (or different columns)
    // are fine.
    const dup = h.levels.find(
      (l) => l.tableId === level.tableId && l.columnName === level.columnName,
    );
    if (dup) return dup;
    const lvl: SchemaCanvasLevel = {
      id: makeId("lvl"),
      name: level.name?.trim() || level.columnName,
      tableId: level.tableId,
      columnName: level.columnName,
      type: level.type,
    };
    h.levels = [...h.levels, lvl];
    this.touch();
    return lvl;
  }

  updateLevel(
    dimensionId: string,
    hierarchyId: string,
    levelId: string,
    patch: Partial<Omit<SchemaCanvasLevel, "id">>,
  ): void {
    const d = this.dimensions.find((d) => d.id === dimensionId);
    const h = d?.hierarchies.find((h) => h.id === hierarchyId);
    const l = h?.levels.find((l) => l.id === levelId);
    if (!l) return;
    Object.assign(l, patch);
    this.touch();
  }

  removeLevel(dimensionId: string, hierarchyId: string, levelId: string): void {
    const d = this.dimensions.find((d) => d.id === dimensionId);
    const h = d?.hierarchies.find((h) => h.id === hierarchyId);
    if (!h) return;
    h.levels = h.levels.filter((l) => l.id !== levelId);
    this.touch();
  }

  /** Move a level one position within its hierarchy (coarse↔fine reorder).
   *  `delta` is -1 (toward coarsest / up) or +1 (toward finest / down). No-op
   *  at the ends. Immutable swap — a new levels array is assigned. */
  moveLevel(
    dimensionId: string,
    hierarchyId: string,
    levelId: string,
    delta: -1 | 1,
  ): void {
    const d = this.dimensions.find((d) => d.id === dimensionId);
    const h = d?.hierarchies.find((h) => h.id === hierarchyId);
    if (!h) return;
    const i = h.levels.findIndex((l) => l.id === levelId);
    const j = i + delta;
    if (i < 0 || j < 0 || j >= h.levels.length) return;
    const next = [...h.levels];
    [next[i], next[j]] = [next[j], next[i]];
    h.levels = next;
    this.touch();
  }

  /** Drop a measure for a numeric column. Defaults to sum. */
  addMeasure(seed: {
    tableId: string;
    columnName: string;
    name?: string;
    aggregator?: SchemaCanvasMeasure["aggregator"];
    /** Percentile fraction (0–100); only meaningful for aggregator 'percentile'. */
    percentile?: number | null;
  }): SchemaCanvasMeasure {
    const aggregator = seed.aggregator ?? "sum";
    const m: SchemaCanvasMeasure = {
      id: makeId("meas"),
      name: seed.name?.trim() || seed.columnName,
      aggregator,
      tableId: seed.tableId,
      columnName: seed.columnName,
      percentile:
        aggregator === "percentile" ? (seed.percentile ?? null) : undefined,
    };
    this.doc.measures = [...this.measures, m];
    this.touch();
    return m;
  }

  updateMeasure(
    measureId: string,
    patch: Partial<Omit<SchemaCanvasMeasure, "id">>,
  ): void {
    const m = this.measures.find((m) => m.id === measureId);
    if (!m) return;
    Object.assign(m, patch);
    this.touch();
  }

  removeMeasure(measureId: string): void {
    this.doc.measures = this.measures.filter((m) => m.id !== measureId);
    this.touch();
  }

  // ── Cubes (measure groups + calculated members) ──────────────────
  // The durable half of the Facts & Measures workbench.  Every mutator
  // is immutable + rides the same touch() → saveToStorage path.  UI-only
  // flags stay in WorkbenchView; the doc carries only the model an
  // external caller (DimSum) needs to create and the exporter to emit.

  /** Read accessor with default — older docs didn't carry `cubes`. */
  get cubes(): SchemaCanvasCube[] {
    return this.doc.cubes ?? [];
  }

  /** Replace the whole cube list.  Used by the workbench's persist path
   *  (mapping its live cubes back into the trimmed doc shape). */
  setCubes(cubes: SchemaCanvasCube[]): void {
    // Preserve cube-scoped time-intelligence metrics across a workbench
    // write-back. The workbench maps its live cubes through `cubeToDoc`,
    // which doesn't carry `timeCalcs` (they're edited via the inspector's
    // store methods on the doc). Without this merge, every workbench sync
    // would silently wipe them. Incoming timeCalcs win when present (e.g. an
    // import); otherwise inherit the existing doc cube's by id.
    const existingTimeCalcs = new Map(
      (this.doc.cubes ?? []).map((c) => [c.id, c.timeCalcs]),
    );
    this.doc.cubes = cubes.map((c) =>
      c.timeCalcs !== undefined
        ? c
        : { ...c, timeCalcs: existingTimeCalcs.get(c.id) },
    );
    this.touch();
  }

  /** Signal the canvas page to re-hydrate the workbench from `doc.cubes`
   *  after an external cube write. */
  bumpWorkbenchReload(): void {
    this.workbenchReloadNonce += 1;
  }

  addCube(seed?: { id?: string; name?: string }): SchemaCanvasCube {
    const name = ensureUnique(
      seed?.name?.trim() || `Cube ${this.cubes.length + 1}`,
      this.cubes.map((c) => c.name),
    );
    const cube: SchemaCanvasCube = {
      id: seed?.id ?? makeId("cube"),
      name,
      measureGroups: [],
      calcs: [],
    };
    this.doc.cubes = [...this.cubes, cube];
    this.touch();
    return cube;
  }

  removeCube(cubeId: string): void {
    this.doc.cubes = this.cubes.filter((c) => c.id !== cubeId);
    this.touch();
  }

  renameCube(cubeId: string, name: string): void {
    const c = this.cubes.find((c) => c.id === cubeId);
    if (!c) return;
    c.name = name;
    this.touch();
  }

  private findCube(cubeId: string): SchemaCanvasCube | undefined {
    return this.cubes.find((c) => c.id === cubeId);
  }

  addMeasureGroup(
    cubeId: string,
    seed: { id?: string; name?: string; factTableId?: string | null },
  ): SchemaCanvasMeasureGroup | null {
    const cube = this.findCube(cubeId);
    if (!cube) return null;
    const name = ensureUnique(
      seed.name?.trim() || `Group ${cube.measureGroups.length + 1}`,
      cube.measureGroups.map((g) => g.name),
    );
    const mg: SchemaCanvasMeasureGroup = {
      id: seed.id ?? makeId("mg"),
      name,
      measureColumns: [],
      factTableId: seed.factTableId ?? null,
      dimensionLinks: [],
    };
    cube.measureGroups = [...cube.measureGroups, mg];
    this.touch();
    return mg;
  }

  removeMeasureGroup(cubeId: string, mgId: string): void {
    const cube = this.findCube(cubeId);
    if (!cube) return;
    cube.measureGroups = cube.measureGroups.filter((g) => g.id !== mgId);
    this.touch();
  }

  renameMeasureGroup(cubeId: string, mgId: string, name: string): void {
    const mg = this.findCube(cubeId)?.measureGroups.find((g) => g.id === mgId);
    if (!mg) return;
    mg.name = name;
    this.touch();
  }

  setMeasureColumns(cubeId: string, mgId: string, cols: string[]): void {
    const mg = this.findCube(cubeId)?.measureGroups.find((g) => g.id === mgId);
    if (!mg) return;
    mg.measureColumns = [...cols];
    this.touch();
  }

  /** Add or remove a single measure column from a group. */
  toggleMeasureColumn(cubeId: string, mgId: string, col: string): void {
    const mg = this.findCube(cubeId)?.measureGroups.find((g) => g.id === mgId);
    if (!mg) return;
    mg.measureColumns = mg.measureColumns.includes(col)
      ? mg.measureColumns.filter((c) => c !== col)
      : [...mg.measureColumns, col];
    this.touch();
  }

  setMeasureGroupFactTable(
    cubeId: string,
    mgId: string,
    tableId: string | null,
  ): void {
    const mg = this.findCube(cubeId)?.measureGroups.find((g) => g.id === mgId);
    if (!mg) return;
    mg.factTableId = tableId;
    this.touch();
  }

  /** Upsert a dimension link on a measure group — keyed by dimensionId. */
  setMeasureGroupDimLink(
    cubeId: string,
    mgId: string,
    link: SchemaCanvasDimensionLink,
  ): void {
    const mg = this.findCube(cubeId)?.measureGroups.find((g) => g.id === mgId);
    if (!mg) return;
    const existing = mg.dimensionLinks ?? [];
    const idx = existing.findIndex((l) => l.dimensionId === link.dimensionId);
    mg.dimensionLinks =
      idx >= 0
        ? existing.map((l, i) => (i === idx ? link : l))
        : [...existing, link];
    this.touch();
  }

  removeMeasureGroupDimLink(
    cubeId: string,
    mgId: string,
    dimensionId: string,
  ): void {
    const mg = this.findCube(cubeId)?.measureGroups.find((g) => g.id === mgId);
    if (!mg?.dimensionLinks) return;
    mg.dimensionLinks = mg.dimensionLinks.filter(
      (l) => l.dimensionId !== dimensionId,
    );
    this.touch();
  }

  addCalc(
    cubeId: string,
    seed: {
      id?: string;
      name?: string;
      tokens?: SchemaCanvasCalc["tokens"];
      formula?: string;
    },
  ): SchemaCanvasCalc | null {
    const cube = this.findCube(cubeId);
    if (!cube) return null;
    const calc: SchemaCanvasCalc = {
      id: seed.id ?? makeId("calc"),
      name: seed.name ?? "",
      tokens: seed.tokens ?? [],
      formula: seed.formula,
    };
    cube.calcs = [...cube.calcs, calc];
    this.touch();
    return calc;
  }

  removeCalc(cubeId: string, calcId: string): void {
    const cube = this.findCube(cubeId);
    if (!cube) return;
    cube.calcs = cube.calcs.filter((c) => c.id !== calcId);
    this.touch();
  }

  // ── Time-intelligence calcs (yoy/pop/ytd/rolling) — cube-scoped ──
  addTimeCalc(
    cubeId: string,
    seed?: Partial<SchemaCanvasTimeCalc>,
  ): SchemaCanvasTimeCalc | null {
    const cube = this.findCube(cubeId);
    if (!cube) return null;
    const tc: SchemaCanvasTimeCalc = {
      id: seed?.id ?? makeId("timecalc"),
      name: seed?.name ?? "",
      type: seed?.type ?? "yoy",
      measure: seed?.measure ?? "",
      timeDimension: seed?.timeDimension,
      window: seed?.window,
      function: seed?.function,
      formatString: seed?.formatString,
    };
    cube.timeCalcs = [...(cube.timeCalcs ?? []), tc];
    this.touch();
    return tc;
  }

  updateTimeCalc(
    cubeId: string,
    timeCalcId: string,
    patch: Partial<Omit<SchemaCanvasTimeCalc, "id">>,
  ): void {
    const cube = this.findCube(cubeId);
    if (!cube?.timeCalcs) return;
    const tc = cube.timeCalcs.find((t) => t.id === timeCalcId);
    if (!tc) return;
    Object.assign(tc, patch);
    this.touch();
  }

  removeTimeCalc(cubeId: string, timeCalcId: string): void {
    const cube = this.findCube(cubeId);
    if (!cube?.timeCalcs) return;
    cube.timeCalcs = cube.timeCalcs.filter((t) => t.id !== timeCalcId);
    this.touch();
  }

  /**
   * Canonical group key for a join — the shared column name when
   * both sides match, otherwise the alphabetised pair joined by `↔`.
   * Stable across direction (source ↔ target) so a swap doesn't
   * shuffle groups around.
   */
  static joinCanonicalKey(j: SchemaCanvasJoin): string {
    if (j.sourceColumnName === j.targetColumnName) return j.sourceColumnName;
    return [j.sourceColumnName, j.targetColumnName].sort().join("↔");
  }

  /** Human label for a join group — falls back to the canonical key
   *  when nothing has been renamed. */
  joinGroupLabelFor(j: SchemaCanvasJoin): string {
    const key = SchemaCanvasStore.joinCanonicalKey(j);
    return this.doc.joinGroupRenames?.[key] ?? key;
  }

  /** Rename a join group. Empty/whitespace `nextLabel` drops the
   *  rename so the group reverts to its canonical key. */
  renameJoinGroup(canonicalKey: string, nextLabel: string): void {
    const trimmed = nextLabel.trim();
    const renames = { ...(this.doc.joinGroupRenames ?? {}) };
    if (!trimmed || trimmed === canonicalKey) {
      delete renames[canonicalKey];
    } else {
      renames[canonicalKey] = trimmed;
    }
    this.doc.joinGroupRenames = renames;
    this.touch();
  }

  setJoinKind(joinId: string, kind: SchemaCanvasJoinKind): void {
    const j = this.doc.joins.find((j) => j.id === joinId);
    if (!j) return;
    j.kind = kind;
    this.touch();
  }

  createGroup(label: string): SchemaCanvasGroup {
    const g: SchemaCanvasGroup = { id: makeId("grp"), label, collapsed: false };
    this.doc.groups.push(g);
    this.touch();
    return g;
  }

  assignTableToGroup(tableId: string, groupId: string | null): void {
    const t = this.doc.tables.find((t) => t.id === tableId);
    if (!t) return;
    t.groupId = groupId;
    this.touch();
  }

  get factTable(): SchemaCanvasTable | null {
    return this.doc.tables.find((t) => t.role === "fact") ?? null;
  }

  get dimensionTables(): SchemaCanvasTable[] {
    return this.doc.tables.filter((t) => t.role !== "fact");
  }

  /** Returns the set of source-table identities (schema.name) currently on the canvas. */
  get tableIdentitiesOnCanvas(): Set<string> {
    return new Set(
      this.doc.tables.map((t) => (t.schema ? `${t.schema}.${t.name}` : t.name)),
    );
  }

  /**
   * Wipe the canvas back to a blank doc for the current connection.
   * Clears UI state too so nothing dangles. Persistence follows — the
   * empty doc replaces the saved one in localStorage.
   */
  resetCanvas(): void {
    this.doc = blankState(this.connectionId);
    this.selectedTableId = null;
    this.selectedJoinId = null;
    this.highlightedColumn = null;
    this.highlightSource = null;
    this.binaryCursor = 0;
    this.sidebarQuery = "";
    this.touch();
  }

  /**
   * Replace the current canvas doc with one loaded from elsewhere
   * (JSON bundle, XML import). The doc is forced onto the active
   * connection id so localStorage stays keyed correctly.
   */
  loadDoc(doc: SchemaCanvasState): void {
    // Snapshot the current doc BEFORE replacing so the toolbar Undo
    // button can revert.  Structured clone to isolate the snapshot
    // from subsequent mutations of `this.doc`.
    // JSON round-trip because structuredClone can't clone Svelte 5
    // `$state` proxies (DataCloneError).
    this.previousDoc = JSON.parse(
      JSON.stringify(this.doc),
    ) as SchemaCanvasState;
    this.doc = { ...doc, connectionId: this.connectionId };
    this.selectedTableId = null;
    this.selectedJoinId = null;
    this.highlightedColumn = null;
    this.highlightSource = null;
    this.binaryCursor = 0;
    this.sidebarQuery = "";
    this.touch();
  }

  /**
   * Restore the last `previousDoc` snapshot as the current doc.  Swaps
   * the snapshot forward-and-back so undo can immediately re-apply
   * (the current doc becomes the new snapshot).  No-op when the
   * snapshot is null.
   */
  undo(): void {
    if (!this.previousDoc) return;
    const swap = this.previousDoc;
    // JSON round-trip because structuredClone can't clone Svelte 5
    // `$state` proxies (DataCloneError).
    this.previousDoc = JSON.parse(
      JSON.stringify(this.doc),
    ) as SchemaCanvasState;
    this.doc = { ...swap, connectionId: this.connectionId };
    this.selectedTableId = null;
    this.selectedJoinId = null;
    this.highlightedColumn = null;
    this.highlightSource = null;
    this.touch();
  }

  /** Discard the pending undo snapshot — e.g. after the user confirms
   *  an AI proposal and no longer wants to revert it. */
  clearPreviousDoc(): void {
    this.previousDoc = null;
  }

  /** Take a snapshot of the current doc so the next big user gesture
   *  is reversible via Undo.  Call at the entry point of a semantic
   *  action (a drag-drop, a commit, an import) rather than per-
   *  mutation, so N adds inside one gesture collapse to one undo
   *  step.  Idempotent within the same gesture: a repeat call inside
   *  the same tick overwrites the previous snapshot (which is what
   *  we want — one snapshot per user intent). */
  snapshotForUndo(): void {
    // JSON round-trip because structuredClone can't clone Svelte 5
    // `$state` proxies (DataCloneError).
    this.previousDoc = JSON.parse(
      JSON.stringify(this.doc),
    ) as SchemaCanvasState;
  }

  /** When true, per-mutation snapshotForUndo is suppressed — the batch
   *  caller (drop, CJM commit, pullIn, linkSimilar) has already taken
   *  ONE snapshot at its entry and doesn't want each addJoin/addTable
   *  inside its loop clobbering that.  Set via `withUndoBatch()`. */
  private undoBatchDepth = 0;

  /** Run `fn` as a single Undo step — take one snapshot at entry, then
   *  suppress the per-mutation snapshots that base methods (addTable,
   *  addJoin, removeTable, removeJoin, removeJoins) would otherwise
   *  take.  Nesting is safe — only the outermost call snapshots. */
  withUndoBatch<T>(fn: () => T): T {
    if (this.undoBatchDepth === 0) this.snapshotForUndo();
    this.undoBatchDepth++;
    try {
      return fn();
    } finally {
      this.undoBatchDepth--;
    }
  }

  /** Take a snapshot unless we're inside a batch (in which case the
   *  batch already snapshotted at entry). Called from every base
   *  mutation so every user-visible action ends up undoable. */
  private maybeSnapshotForUndo(): void {
    if (this.undoBatchDepth === 0) this.snapshotForUndo();
  }

  /**
   * Click-to-join entry point. State machine:
   *  - no pending source → arm (stash this column as the source).
   *  - click the SAME (table, column) again → cancel (clear).
   *  - click a different column on the SAME table → replace the pending
   *    source (self-joins aren't modelled here, so silently re-arm).
   *  - click a column on a DIFFERENT table → commit the join via
   *    `addJoin` (which itself dedupes by exact tuple + touches the
   *    doc), then clear the pending source.
   *
   * `pendingJoinSource` is UI-only — no `touch()` here, only `addJoin`
   * mutates the persisted doc.
   */
  /** Tap E → toggle pick mode.  When ON, column clicks accumulate
   *  into eShiftPicks instead of arming a single click-to-join.
   *  Tapping E again turns the mode OFF but does NOT clear picks —
   *  the panel persists until Clear or Match.  Idempotent on
   *  keyboard auto-repeat. */
  togglePickMode(): void {
    this.pickModeActive = !this.pickModeActive;
  }
  enterPickMode(): void {
    this.pickModeActive = true;
  }
  exitPickMode(): void {
    this.pickModeActive = false;
  }

  /** Add (or toggle off) a column to the pick set.  Enforces at most
   *  ONE column per table — joins are one-to-one, so picking a second
   *  column on a table that already has one REPLACES the previous
   *  pick for that table (otherwise you'd end up trying to "merge"
   *  two columns of the same table into a single joined thing, which
   *  is meaningless).  Re-clicking the same (table, column) toggles
   *  it off. */
  pushEShiftPick(tableId: string, columnName: string): void {
    const sameColIdx = this.eShiftPicks.findIndex(
      (p) => p.tableId === tableId && p.columnName === columnName,
    );
    if (sameColIdx >= 0) {
      // Toggle off — user re-clicked the same pick.
      const next = this.eShiftPicks.slice();
      next.splice(sameColIdx, 1);
      this.eShiftPicks = next;
      return;
    }
    const sameTableIdx = this.eShiftPicks.findIndex(
      (p) => p.tableId === tableId,
    );
    if (sameTableIdx >= 0) {
      // Replace the existing pick on this table — one column per table.
      const next = this.eShiftPicks.slice();
      next[sameTableIdx] = { tableId, columnName };
      this.eShiftPicks = next;
      return;
    }
    // New table entirely — append.
    this.eShiftPicks = [...this.eShiftPicks, { tableId, columnName }];
  }

  /** Commit picks → joins, using a STAR pattern (pick[0] = origin,
   *  wires to each subsequent pick).  The optional `groupName` is
   *  written into `joinGroupRenames` for every new join's canonical
   *  key so the joins-panel renders them all under one label —
   *  letting picks with DIFFERENT column names ("product_id" ↔
   *  "prod_id" ↔ "pid") collapse into a single semantic group like
   *  "Product Key".  Less than two picks → no-op.  Mesh + chain
   *  patterns are not exposed yet; star is the safe default. */
  commitEShiftPicks(groupName?: string): void {
    const picks = this.eShiftPicks;
    this.eShiftPicks = [];
    this.pickModeActive = false;
    if (picks.length < 2) return;
    const trimmedName = groupName?.trim();
    const origin = picks[0];
    const renames = { ...(this.doc.joinGroupRenames ?? {}) };
    let joinsCreated = 0;
    // One Undo step per commit — every addJoin inside skips its
    // per-mutation snapshot via the batch guard.
    this.withUndoBatch(() => {
      for (let i = 1; i < picks.length; i++) {
        const peer = picks[i];
        if (origin.tableId === peer.tableId) continue;
        const created = this.addJoin({
          sourceTableId: origin.tableId,
          sourceColumnName: origin.columnName,
          targetTableId: peer.tableId,
          targetColumnName: peer.columnName,
          kind: "inner",
        });
        joinsCreated++;
        if (trimmedName) {
          const canonicalKey = SchemaCanvasStore.joinCanonicalKey(created);
          renames[canonicalKey] = trimmedName;
        }
      }
      if (trimmedName) {
        this.doc.joinGroupRenames = renames;
        this.touch();
      }
    });
    // Overwrite the per-join feedback with a batch summary so the
    // user sees ONE toast, not N.
    if (joinsCreated > 1) {
      const label = trimmedName ? ` under "${trimmedName}"` : "";
      this.pushJoinFeedback(`${joinsCreated} joins created${label}`);
    } else if (joinsCreated === 1 && trimmedName) {
      // Single join with a named group — mention the label so the
      // user knows the rename landed.
      this.pushJoinFeedback(`Join created as "${trimmedName}"`);
    }
  }

  /** Clear picks + exit pick mode.  Wired to ESC and the picks-
   *  panel "Clear" button. */
  cancelEShift(): void {
    this.pickModeActive = false;
    this.eShiftPicks = [];
  }

  /** User-typed sidebar search — updates the query AND clears the
   *  click-driven highlight so the sidebar and the "Pull in N
   *  matching tables" pill / "MATCHING X" active-set panel can't
   *  end up talking about different columns.  Column-click driven
   *  updates go through the internal `sidebarQuery = X` path (with
   *  the highlight set alongside) so they stay coherent. */
  setSidebarQueryFromUser(q: string): void {
    this.sidebarQuery = q;
    this.highlightedColumn = null;
    this.highlightOriginTableId = null;
    this.highlightSource = null;
  }

  /** Sensible default label for a group of picked columns — used to
   *  prefill the group-name input and as the auto-name when the user
   *  hits Enter without typing anything.  If every pick shares the
   *  same column name, use it verbatim; otherwise fall back to the
   *  most common name across the picks (ties broken by first-seen).
   *  Empty string when there are no picks. */
  autoPickGroupName(): string {
    if (this.eShiftPicks.length === 0) return "";
    const counts = new Map<string, number>();
    for (const p of this.eShiftPicks) {
      counts.set(p.columnName, (counts.get(p.columnName) ?? 0) + 1);
    }
    let bestName = this.eShiftPicks[0].columnName;
    let bestCount = 0;
    for (const [name, count] of counts) {
      if (count > bestCount) {
        bestName = name;
        bestCount = count;
      }
    }
    return bestName;
  }

  /** Arm a pathfinder slot — the next column click on the canvas
   *  writes into that slot.  Passing `null` disarms.  Clicking the
   *  same slot twice while armed toggles it off. */
  armPathfinder(slot: "start" | "end" | null): void {
    this.pathfinderArmed = this.pathfinderArmed === slot ? null : slot;
    // Stale result becomes meaningless the moment endpoints might
    // change — clear it so the UI doesn't display a path that no
    // longer matches the inputs.
    this.pathfinderResult = null;
  }

  /** Set an endpoint explicitly (used by tryColumnClickJoin when
   *  pathfinderArmed is set). Clears the armed flag once written. */
  setPathfinderEndpoint(
    slot: "start" | "end",
    endpoint: PathfinderEndpoint,
  ): void {
    if (slot === "start") this.pathfinderStart = endpoint;
    else this.pathfinderEnd = endpoint;
    this.pathfinderArmed = null;
    this.pathfinderResult = null;
  }

  /** Wipe every pathfinder bit — slots, armed flag, result.  Wired
   *  to the panel's Clear button. */
  clearPathfinder(): void {
    this.pathfinderArmed = null;
    this.pathfinderStart = null;
    this.pathfinderEnd = null;
    this.pathfinderResult = null;
  }

  /**
   * BFS from `pathfinderStart.tableId` to `pathfinderEnd.tableId`.
   * First pass uses existing joins only.  If no path exists, a
   * second pass adds virtual edges for column-name collisions
   * (case-insensitive) so the result can propose missing joins for
   * the user to commit via `applyPathfinderJoins`.  Writes the
   * shortest chain (or null when no path is possible) into
   * `pathfinderResult`.
   */
  findPath(): void {
    const start = this.pathfinderStart;
    const end = this.pathfinderEnd;
    if (!start || !end) {
      this.pathfinderResult = null;
      return;
    }
    // Same-table trivial case — no path, just return an empty steps
    // list so the UI can say "already on the same table".
    if (start.tableId === end.tableId) {
      this.pathfinderResult = { steps: [], hasGaps: false };
      return;
    }

    type Edge = {
      toTableId: string;
      fromColumnName: string;
      toColumnName: string;
      joinId: string | null; // null = virtual (proposed)
    };

    // Build adjacency from existing joins first.  Joins are undirected
    // for traversal — a path from A to B can use a join wired B→A.
    const adj = new Map<string, Edge[]>();
    const pushEdge = (fromId: string, edge: Edge) => {
      const list = adj.get(fromId);
      if (list) list.push(edge);
      else adj.set(fromId, [edge]);
    };
    for (const j of this.doc.joins) {
      pushEdge(j.sourceTableId, {
        toTableId: j.targetTableId,
        fromColumnName: j.sourceColumnName,
        toColumnName: j.targetColumnName,
        joinId: j.id,
      });
      pushEdge(j.targetTableId, {
        toTableId: j.sourceTableId,
        fromColumnName: j.targetColumnName,
        toColumnName: j.sourceColumnName,
        joinId: j.id,
      });
    }

    const bfs = (): PathfinderStep[] | null => {
      const prev = new Map<string, { fromId: string; edge: Edge }>();
      const seen = new Set<string>([start.tableId]);
      const queue: string[] = [start.tableId];
      while (queue.length > 0) {
        const cur = queue.shift()!;
        if (cur === end.tableId) {
          const steps: PathfinderStep[] = [];
          let nodeId: string | undefined = cur;
          while (nodeId && nodeId !== start.tableId) {
            const back = prev.get(nodeId);
            if (!back) break;
            steps.unshift({
              fromTableId: back.fromId,
              fromColumnName: back.edge.fromColumnName,
              toTableId: back.edge.toTableId,
              toColumnName: back.edge.toColumnName,
              existingJoinId: back.edge.joinId,
            });
            nodeId = back.fromId;
          }
          return steps;
        }
        const edges = adj.get(cur) ?? [];
        for (const edge of edges) {
          if (seen.has(edge.toTableId)) continue;
          seen.add(edge.toTableId);
          prev.set(edge.toTableId, { fromId: cur, edge });
          queue.push(edge.toTableId);
        }
      }
      return null;
    };

    const existingOnly = bfs();
    if (existingOnly && existingOnly.length > 0) {
      this.pathfinderResult = { steps: existingOnly, hasGaps: false };
      return;
    }

    // Fallback — re-build adjacency including virtual edges built from
    // shared column names across on-canvas tables, then BFS again.
    // The result will probably include gaps (virtual edges with
    // joinId: null) that "Create path joins" can wire up.
    const byNameLower = new Map<
      string,
      Array<{ tableId: string; columnName: string }>
    >();
    for (const t of this.doc.tables) {
      for (const c of t.columns) {
        const key = c.name.toLowerCase();
        const bucket = byNameLower.get(key);
        if (bucket) bucket.push({ tableId: t.id, columnName: c.name });
        else byNameLower.set(key, [{ tableId: t.id, columnName: c.name }]);
      }
    }
    for (const bucket of byNameLower.values()) {
      if (bucket.length < 2) continue;
      for (let i = 0; i < bucket.length; i++) {
        for (let k = i + 1; k < bucket.length; k++) {
          const a = bucket[i];
          const b = bucket[k];
          if (a.tableId === b.tableId) continue;
          // Skip pairs already wired in either direction — would
          // otherwise inflate the graph with duplicate edges.
          const already = this.doc.joins.some((j) => {
            const f =
              j.sourceTableId === a.tableId &&
              j.sourceColumnName === a.columnName &&
              j.targetTableId === b.tableId &&
              j.targetColumnName === b.columnName;
            const r =
              j.sourceTableId === b.tableId &&
              j.sourceColumnName === b.columnName &&
              j.targetTableId === a.tableId &&
              j.targetColumnName === a.columnName;
            return f || r;
          });
          if (already) continue;
          pushEdge(a.tableId, {
            toTableId: b.tableId,
            fromColumnName: a.columnName,
            toColumnName: b.columnName,
            joinId: null,
          });
          pushEdge(b.tableId, {
            toTableId: a.tableId,
            fromColumnName: b.columnName,
            toColumnName: a.columnName,
            joinId: null,
          });
        }
      }
    }
    const withVirtual = bfs();
    if (withVirtual && withVirtual.length > 0) {
      this.pathfinderResult = {
        steps: withVirtual,
        hasGaps: withVirtual.some((s) => s.existingJoinId === null),
      };
      return;
    }
    this.pathfinderResult = null;
  }

  /** Wire up every virtual (gap) step in `pathfinderResult` as a real
   *  join.  No-op when there's no result or no gaps.  Returns the
   *  number actually added. */
  applyPathfinderJoins(): number {
    const result = this.pathfinderResult;
    if (!result || !result.hasGaps) return 0;
    let added = 0;
    for (const step of result.steps) {
      if (step.existingJoinId !== null) continue;
      const before = this.doc.joins.length;
      this.addJoin({
        sourceTableId: step.fromTableId,
        sourceColumnName: step.fromColumnName,
        targetTableId: step.toTableId,
        targetColumnName: step.toColumnName,
        kind: "inner",
      });
      if (this.doc.joins.length > before) added++;
    }
    // Re-run the search so the result reflects post-commit state —
    // what was a virtual step is now an existing join, and hasGaps
    // flips false.
    if (added > 0) this.findPath();
    return added;
  }

  tryColumnClickJoin(tableId: string, columnName: string): void {
    // A column click is a column-level intent — not a table-focus
    // trigger. SvelteFlow marks the node selected on any click inside
    // it, which would otherwise fire the auto-focus $effect and
    // light up EVERY join column on the table. Explicitly bail out
    // of table-focus + selection so the only thing the user sees is
    // the green column highlight.
    this.selectedTableId = null;
    this.focusKind = "none";
    this.focusKey = null;
    // Pathfinder armed → the click captures an endpoint instead of
    // running the normal click-to-highlight flow.
    if (this.pathfinderArmed !== null) {
      this.setPathfinderEndpoint(this.pathfinderArmed, { tableId, columnName });
      return;
    }
    // Click-to-join (the hidden two-step where the first click armed
    // a `pendingJoinSource` and the second click on a different
    // table committed a join) was removed because users hit it by
    // accident — clicking around to look at columns silently armed
    // the next click. Joins now come from explicit gestures only:
    // drag handle-to-handle, Create Joins Mode (⌘J), or sidebar drag with
    // Auto-join on. Column click is highlight-only.
    const alreadyHighlighted =
      this.highlightedColumn === columnName &&
      this.highlightOriginTableId === tableId;
    if (alreadyHighlighted) {
      this.highlightedColumn = null;
      this.highlightOriginTableId = null;
      this.highlightSource = null;
      this.sidebarQuery = "";
      return;
    }
    this.highlightedColumn = columnName;
    this.highlightOriginTableId = tableId;
    this.highlightSource = "click";
    this.sidebarQuery = columnName;
  }

  /**
   * Highlight a column name across the whole canvas + push the same
   * name into the sidebar search so the user sees every table that has
   * it. Click the same column again to clear. Switching the highlight
   * to a different name replaces it.
   */
  highlightColumn(columnName: string): void {
    if (this.highlightedColumn === columnName) {
      this.highlightedColumn = null;
      this.highlightSource = null;
      this.sidebarQuery = "";
      return;
    }
    this.highlightedColumn = columnName;
    this.highlightSource = "click";
    this.sidebarQuery = columnName;
  }
}
