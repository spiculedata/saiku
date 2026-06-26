import {
  cancelQuery,
  executeQuery,
  executeQueryAsync,
  newQuery,
  type AxisLocation,
  type QueryResult,
  type ThinHierarchy,
  type ThinMeasure,
  type ThinQuery,
} from "$lib/api/query";
import type { SaikuCube } from "$lib/api/discover";
import { registerPendingOp } from "$lib/api/http";
import { DEFAULT_CHART_OPTIONS, type ChartOptions, type ChartType } from "$lib/views/chartTypes";

export type ViewMode = "grid" | "chart" | "stats" | "sparkline" | "sparkbar";

/** Per-tab snapshot of the user-visible query state. Tabs store hands
 *  one of these around when switching tabs to preserve work in flight.
 *  Transient flags (running, error, abortController) deliberately
 *  excluded — we cancel any in-flight query on snapshot rather than try
 *  to carry async state across the swap. */
export interface QueryStateSnapshot {
  current: ThinQuery | null;
  result: QueryResult | null;
  savedPath: string | null;
  viewMode: ViewMode;
  chartType: ChartType;
  chartOptions: ChartOptions;
  dirty: boolean;
  dirtyCount: number;
  /** Per-tab undo history. Stays attached to the tab through snapshotAndReset
   *  / restore so tab switches don't wipe undo stacks. */
  past?: HistoryEntry[];
  future?: HistoryEntry[];
}

/**
 * One slot in the undo / redo stack — a deep-clone of every queryModel + view
 * field that the user can mutate. {@code result} is intentionally excluded;
 * re-running the restored model is cheaper than carrying the cellset around,
 * and stale results would mislead the user while the live query re-runs.
 */
export interface HistoryEntry {
  current: ThinQuery | null;
  viewMode: ViewMode;
  chartType: ChartType;
  chartOptions: ChartOptions;
}

/** Build a fresh empty snapshot — what a brand-new tab starts with. */
export function blankSnapshot(): QueryStateSnapshot {
  return {
    current: null,
    result: null,
    savedPath: null,
    viewMode: "grid",
    chartType: "bar",
    chartOptions: { ...DEFAULT_CHART_OPTIONS },
    dirty: false,
    dirtyCount: 0,
    past: [],
    future: [],
  };
}

/** Ensure a hydrated/loaded ThinQuery has a name safe to embed in a REST URL
 *  path segment. Saved-query files written by older builds stored the file
 *  path as `name`, which contains `/`. Jetty 12's strict URI compliance
 *  rejects %2F-encoded slashes in path segments with a 400, so any
 *  /rest/saiku/api/query/{name}/... request would fail.
 *  We regenerate to a fresh UUID when the stored name is empty or contains
 *  a slash. The on-disk savedPath is tracked separately on the store. */
function withSafeName(q: ThinQuery): ThinQuery {
  if (!q.name || q.name.includes("/")) {
    return { ...q, name: cryptoUuid() };
  }
  return q;
}

function cryptoUuid(): string {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export interface LevelDrop {
  dimensionName: string;
  dimensionUniqueName: string;
  hierarchyName: string;
  hierarchyUniqueName: string;
  hierarchyCaption: string;
  levelName: string;
  levelCaption: string;
}

function readBoolLS(key: string, fallback: boolean): boolean {
  if (typeof localStorage === "undefined") return fallback;
  const raw = localStorage.getItem(key);
  if (raw === null) return fallback;
  return raw === "1" || raw === "true";
}

function writeBoolLS(key: string, value: boolean): void {
  if (typeof localStorage === "undefined") return;
  localStorage.setItem(key, value ? "1" : "0");
}

class QueryStore {
  current = $state<ThinQuery | null>(null);
  result = $state<QueryResult | null>(null);
  running = $state<boolean>(false);
  error = $state<string | null>(null);
  /** Optional raw engine message for the error callout's "Show details"
   *  panel. Operators copy-paste this into a support ticket — users see
   *  the friendly text in {@link error}. */
  errorDetail = $state<string | null>(null);
  dirty = $state<boolean>(false);
  dirtyCount = $state<number>(0);
  savedPath = $state<string | null>(null);
  autorun = $state<boolean>(true);
  viewMode = $state<ViewMode>("grid");
  chartType = $state<ChartType>("bar");
  chartOptions = $state<ChartOptions>({ ...DEFAULT_CHART_OPTIONS });
  #async = $state<boolean>(readBoolLS("saiku_async", false));
  runningQueryId = $state<string | null>(null);
  runningElapsedMs = $state<number>(0);

  /**
   * Undo / redo stacks. `past` holds states from before each mutation; the
   * most recent push is the immediate predecessor of `current`. `future` holds
   * states that have been undone (and can be redone). On any forward mutation
   * (not undo/redo), `future` is cleared so we don't have stale branches.
   *
   * Capped at MAX_HISTORY entries each — beyond that, old entries fall off the
   * bottom. Per-tab: the tabs store snapshot/restore carries these through.
   */
  past = $state<HistoryEntry[]>([]);
  future = $state<HistoryEntry[]>([]);

  /** Reactive flags for toolbar / keyboard-shortcut visibility. */
  canUndo = $derived(this.past.length > 0);
  canRedo = $derived(this.future.length > 0);

  private static readonly MAX_HISTORY = 50;

  private abortController: AbortController | null = null;
  private elapsedTimer: ReturnType<typeof setInterval> | null = null;
  private unregisterPendingOp: (() => void) | null = null;

  get async(): boolean {
    return this.#async;
  }
  set async(value: boolean) {
    this.#async = value;
    writeBoolLS("saiku_async", value);
  }

  private markDirty(): void {
    this.dirty = true;
    this.dirtyCount++;
    if (this.autorun && this.hasRunnableShape()) {
      void this.run();
    }
  }

  // --- Undo / redo machinery ---

  /**
   * Build a HistoryEntry from the current store state. Uses {@code structuredClone}
   * so the snapshot is a deep copy — mutating the live query afterwards must
   * not bleed back into the recorded entry. Browsers we ship to all support
   * structuredClone (Safari 15.4+, Chrome 98+, Firefox 94+).
   */
  private snapshotForHistory(): HistoryEntry {
    return {
      current: this.current ? structuredClone($state.snapshot(this.current)) : null,
      viewMode: this.viewMode,
      chartType: this.chartType,
      chartOptions: structuredClone($state.snapshot(this.chartOptions)),
    };
  }

  /**
   * Push the CURRENT state onto the undo stack before a mutation. Every public
   * mutator (includeLevel, removeLevel, setLevelSelection, addMeasure, etc.,
   * plus the AI Ask handoffs in Workspace.svelte) calls this as its first
   * line. Clears the redo stack — any forward mutation invalidates the
   * previously-undone branch. Capped at MAX_HISTORY entries.
   *
   * Public because Workspace.svelte calls it from the AI Ask handlers before
   * replacing queryModel / viewMode; everything inside this store should call
   * it from its own mutators.
   */
  captureForUndo(): void {
    this.past.push(this.snapshotForHistory());
    if (this.past.length > QueryStore.MAX_HISTORY) {
      this.past.shift();
    }
    if (this.future.length) this.future = [];
  }

  /**
   * Apply a HistoryEntry to the live store state. Used by undo()/redo() —
   * NOT by load operations (initFor / hydrate / loadFromJson / reset), which
   * clear history rather than walk it.
   *
   * `entry` came from this.past / this.future which are $state arrays — their
   * values are wrapped in Svelte 5 Proxies. `structuredClone()` throws
   * "Proxy object could not be cloned" on those, which crashes undo / redo
   * AND any path that triggers captureForUndo (every AI Ask rewrite, every
   * chip mutation). $state.snapshot() unwraps the proxy first, returning a
   * plain non-reactive copy that structuredClone (or assignment) can handle.
   */
  private applyHistory(entry: HistoryEntry): void {
    const plain = $state.snapshot(entry) as HistoryEntry;
    this.current = plain.current ? structuredClone(plain.current) : null;
    this.viewMode = plain.viewMode;
    this.chartType = plain.chartType;
    this.chartOptions = structuredClone(plain.chartOptions);
    this.dirty = true;
    this.dirtyCount++;
    // Undo/redo should auto-rerun if the restored state is runnable. We bypass
    // markDirty's auto-run (which would loop with the in-flight cancel logic)
    // and call run() directly; the run() guard handles in-flight cancellation.
    if (this.autorun && this.hasRunnableShape()) {
      void this.run();
    }
  }

  /** Pop the most recent past state into present; push current to future. */
  undo(): void {
    if (!this.past.length) return;
    const present = this.snapshotForHistory();
    const prior = this.past.pop()!;
    this.future.push(present);
    this.applyHistory(prior);
  }

  /** Pop the most recent future state into present; push current to past. */
  redo(): void {
    if (!this.future.length) return;
    const present = this.snapshotForHistory();
    const next = this.future.pop()!;
    this.past.push(present);
    if (this.past.length > QueryStore.MAX_HISTORY) this.past.shift();
    this.applyHistory(next);
  }

  /** Clear both stacks. Called by load operations that replace the whole
   *  query (initFor, hydrate, loadFromJson, reset). */
  private clearHistory(): void {
    this.past = [];
    this.future = [];
  }

  initFor(cube: SaikuCube): void {
    this.current = newQuery(cube);
    this.result = null;
    this.error = null;
    this.errorDetail = null;
    this.dirty = false;
    this.dirtyCount = 0;
    this.savedPath = null;
    // Brand-new query starts with empty undo history — there's nothing
    // meaningful to undo back to. The user's previous tab still has its own
    // history (per-tab stacks).
    this.clearHistory();
  }

  loadFromJson(raw: string, path: string): void {
    const parsed = JSON.parse(raw) as ThinQuery;
    this.current = withSafeName(parsed);
    this.result = null;
    this.error = null;
    this.errorDetail = null;
    this.dirty = false;
    this.dirtyCount = 0;
    this.savedPath = path;
    this.clearHistory();
  }

  /** Replace the current query wholesale (e.g. from a deep-link hydrate or an
   *  opened saved query) without running it. Caller decides whether to run. */
  hydrate(q: ThinQuery, savedPath: string | null = null): void {
    this.current = withSafeName(q);
    this.result = null;
    this.error = null;
    this.errorDetail = null;
    this.dirty = false;
    this.dirtyCount = 0;
    this.savedPath = savedPath;
    this.clearHistory();
  }

  markSaved(path: string): void {
    // Track the saved path separately from the server-side query name. We
    // used to overwrite `current.name` with the path, but Jetty 12's strict
    // URI compliance rejects %2F-encoded slashes in path segments, so any
    // request like /rest/saiku/api/query/{name}/drillthrough would 400 once
    // the name turned into "folder/file.saiku".
    this.savedPath = path;
    this.dirty = false;
    this.dirtyCount = 0;
  }

  reset(): void {
    this.current = null;
    this.result = null;
    this.error = null;
    this.errorDetail = null;
    this.dirty = false;
    this.dirtyCount = 0;
    this.savedPath = null;
    this.clearHistory();
  }

  /** Capture a serialisable snapshot of all per-tab user-visible state so
   *  the tabs store can stash it before switching to a different tab.
   *  Excludes transient flags (running, error, runningQueryId, elapsed)
   *  — those don't survive tab switches; we cancel any in-flight query
   *  in {@link snapshotAndReset} before restoring. */
  snapshot(): QueryStateSnapshot {
    return {
      current: this.current,
      result: this.result,
      savedPath: this.savedPath,
      viewMode: this.viewMode,
      chartType: this.chartType,
      chartOptions: this.chartOptions,
      dirty: this.dirty,
      dirtyCount: this.dirtyCount,
      // Carry the per-tab undo stacks through tab snapshot/restore. Without
      // this, switching tabs would wipe undo history every time.
      past: this.past,
      future: this.future,
    };
  }

  /** Cancel any in-flight query (so its response can't land on the wrong
   *  tab), capture state, then return it. Used by the tabs store right
   *  before {@link restore} swaps in another tab. */
  snapshotAndReset(): QueryStateSnapshot {
    if (this.running) this.cancel();
    return this.snapshot();
  }

  /** Hydrate this store from a previously-captured snapshot. Used by the
   *  tabs store on tab switch. */
  restore(s: QueryStateSnapshot): void {
    if (this.running) this.cancel();
    this.current = s.current;
    this.result = s.result;
    this.savedPath = s.savedPath;
    this.viewMode = s.viewMode;
    this.chartType = s.chartType;
    this.chartOptions = s.chartOptions;
    this.dirty = s.dirty;
    this.dirtyCount = s.dirtyCount;
    this.error = null;
    this.running = false;
    this.runningQueryId = null;
    this.runningElapsedMs = 0;
    // Carry the per-tab undo stacks through restore. Default empty arrays
    // for snapshots saved before this field was introduced.
    this.past = s.past ?? [];
    this.future = s.future ?? [];
  }

  private findAxisForHierarchy(uniqueName: string): AxisLocation | null {
    if (!this.current?.queryModel) return null;
    const axes = this.current.queryModel.axes;
    for (const loc of Object.keys(axes) as AxisLocation[]) {
      if (axes[loc].hierarchies.some((h) => h.name === uniqueName)) return loc;
    }
    return null;
  }

  includeLevel(axis: AxisLocation, drop: LevelDrop, position = -1): void {
    if (!this.current?.queryModel) return;
    this.captureForUndo();
    const model = this.current.queryModel;
    const existing = this.findAxisForHierarchy(drop.hierarchyUniqueName);

    let hier: ThinHierarchy;
    if (existing) {
      const fromAxis = model.axes[existing];
      const idx = fromAxis.hierarchies.findIndex((h) => h.name === drop.hierarchyUniqueName);
      hier = fromAxis.hierarchies[idx];
      fromAxis.hierarchies.splice(idx, 1);
    } else {
      hier = {
        name: drop.hierarchyUniqueName,
        caption: drop.hierarchyCaption,
        dimension: drop.dimensionName,
        levels: {},
        cmembers: {},
      };
    }
    hier.levels[drop.levelName] = { name: drop.levelName };

    const target = model.axes[axis].hierarchies;
    if (position >= 0 && position < target.length) {
      target.splice(position, 0, hier);
    } else {
      target.push(hier);
    }
    this.markDirty();
  }

  /** Move an existing hierarchy from its current axis to another axis,
   *  preserving its levels/cmembers. Used by chip drag-and-drop between
   *  drop-zones. No-op if the hierarchy is already on {@code toAxis}. */
  moveHierarchyToAxis(hierarchyName: string, toAxis: AxisLocation): void {
    if (!this.current?.queryModel) return;
    const fromAxis = this.findAxisForHierarchy(hierarchyName);
    if (!fromAxis || fromAxis === toAxis) return;
    this.captureForUndo();
    const model = this.current.queryModel;
    const idx = model.axes[fromAxis].hierarchies.findIndex((h) => h.name === hierarchyName);
    if (idx < 0) return;
    const [hier] = model.axes[fromAxis].hierarchies.splice(idx, 1);
    model.axes[toAxis].hierarchies.push(hier);
    this.markDirty();
  }

  /** Reorder a hierarchy within its own axis by inserting it just before `targetName`.
   *  If `targetName` is null, move to the end. No-op if source === target. */
  reorderHierarchy(axis: AxisLocation, sourceName: string, targetName: string | null): void {
    if (!this.current?.queryModel) return;
    const axisModel = this.current.queryModel.axes[axis];
    const list = axisModel.hierarchies;
    const fromIdx = list.findIndex((h) => h.name === sourceName);
    if (fromIdx < 0) return;
    this.captureForUndo();
    const [hier] = list.splice(fromIdx, 1);
    if (targetName == null) {
      list.push(hier);
    } else {
      const toIdx = list.findIndex((h) => h.name === targetName);
      if (toIdx < 0) list.push(hier);
      else list.splice(toIdx, 0, hier);
    }
    this.markDirty();
  }

  /** Reorder a measure on the COLUMNS axis by inserting it just before `targetUniqueName`.
   *  If `targetUniqueName` is null, move to the end. */
  reorderMeasure(sourceUniqueName: string, targetUniqueName: string | null): void {
    if (!this.current?.queryModel) return;
    const details = this.current.queryModel.details;
    const list = details.measures;
    const fromIdx = list.findIndex((m) => m.uniqueName === sourceUniqueName);
    if (fromIdx < 0) return;
    this.captureForUndo();
    const [m] = list.splice(fromIdx, 1);
    if (targetUniqueName == null) {
      list.push(m);
    } else {
      const toIdx = list.findIndex((x) => x.uniqueName === targetUniqueName);
      if (toIdx < 0) list.push(m);
      else list.splice(toIdx, 0, m);
    }
    this.markDirty();
  }

  removeHierarchy(hierarchyName: string): void {
    if (!this.current?.queryModel) return;
    const loc = this.findAxisForHierarchy(hierarchyName);
    if (!loc) return;
    this.captureForUndo();
    const axis = this.current.queryModel.axes[loc];
    axis.hierarchies = axis.hierarchies.filter((h) => h.name !== hierarchyName);
    this.markDirty();
  }

  /** Remove a single level from a hierarchy chip. If it was the only
   *  level, the hierarchy itself is dropped. Mirrors what the cellset
   *  header's "Remove Level" right-click does, so both entry points
   *  agree on what "remove" means. */
  removeLevel(hierarchyName: string, levelName: string): void {
    if (!this.current?.queryModel) return;
    const loc = this.findAxisForHierarchy(hierarchyName);
    if (!loc) return;
    const hier = this.current.queryModel.axes[loc].hierarchies.find((h) => h.name === hierarchyName);
    if (!hier) return;
    if (!(levelName in hier.levels)) return;
    this.captureForUndo();
    delete hier.levels[levelName];
    if (Object.keys(hier.levels).length === 0) {
      // removeHierarchy() would call captureForUndo() too — drop the redundant
      // capture by inlining the deletion. Single-undo, not two.
      const axis = this.current.queryModel.axes[loc];
      axis.hierarchies = axis.hierarchies.filter((h) => h.name !== hierarchyName);
    }
    this.markDirty();
  }

  addMeasure(m: ThinMeasure): void {
    if (!this.current?.queryModel) return;
    const list = this.current.queryModel.details.measures;
    if (list.some((x) => x.uniqueName === m.uniqueName)) return;
    this.captureForUndo();
    list.push(m);
    this.markDirty();
  }

  setMeasures(list: ThinMeasure[]): void {
    if (!this.current?.queryModel) return;
    this.captureForUndo();
    this.current.queryModel.details.measures = [...list];
    this.markDirty();
  }

  removeMeasure(uniqueName: string): void {
    if (!this.current?.queryModel) return;
    this.captureForUndo();
    const details = this.current.queryModel.details;
    details.measures = details.measures.filter((m) => m.uniqueName !== uniqueName);
    this.markDirty();
  }

  /** Where measures are projected onto the cellset:
   *   axis ∈ {COLUMNS, ROWS}  — which axis the measure column group sits on
   *   location ∈ {TOP, BOTTOM} — first or last in that axis's ordering
   *
   *  Mirrors the four "Position" options that the pre-rewrite Backbone
   *  UI exposed off the Measures-panel gear (see WorkspaceDropZone.js
   *  measure_action). The model and `Fat.convertDetails` already
   *  honour any AxisLocation here — this method just makes the
   *  flexibility user-controllable again. */
  setMeasuresPlacement(axis: AxisLocation, location: "TOP" | "BOTTOM"): void {
    if (!this.current?.queryModel) return;
    const d = this.current.queryModel.details;
    if (d.axis === axis && d.location === location) return;
    this.captureForUndo();
    d.axis = axis;
    d.location = location;
    this.markDirty();
  }

  swapAxes(): void {
    if (!this.current?.queryModel) return;
    this.captureForUndo();
    const m = this.current.queryModel;
    const a = m.axes.ROWS;
    const b = m.axes.COLUMNS;
    m.axes.ROWS = { ...b, location: "ROWS" };
    m.axes.COLUMNS = { ...a, location: "COLUMNS" };
    this.markDirty();
  }

  setNonEmpty(axis: AxisLocation, nonEmpty: boolean): void {
    if (!this.current?.queryModel) return;
    this.captureForUndo();
    this.current.queryModel.axes[axis].nonEmpty = nonEmpty;
    this.markDirty();
  }

  setLevelSelection(
    hierarchyName: string,
    levelName: string,
    memberUniqueNames: string[],
    type: "INCLUSION" | "EXCLUSION",
  ): void {
    if (!this.current?.queryModel) return;
    for (const loc of Object.keys(this.current.queryModel.axes) as AxisLocation[]) {
      const axis = this.current.queryModel.axes[loc];
      const hier = axis.hierarchies.find((h) => h.name === hierarchyName);
      if (!hier) continue;
      const level = hier.levels[levelName];
      if (!level) continue;
      this.captureForUndo();
      if (memberUniqueNames.length === 0) {
        delete level.selection;
      } else {
        level.selection = {
          type,
          members: memberUniqueNames.map((uniqueName) => ({ uniqueName })),
        };
      }
      this.markDirty();
      return;
    }
  }

  getLevelSelection(
    hierarchyName: string,
    levelName: string,
  ): { memberUniqueNames: string[]; type: "INCLUSION" | "EXCLUSION" } {
    if (!this.current?.queryModel) return { memberUniqueNames: [], type: "INCLUSION" };
    for (const loc of Object.keys(this.current.queryModel.axes) as AxisLocation[]) {
      const hier = this.current.queryModel.axes[loc].hierarchies.find(
        (h) => h.name === hierarchyName,
      );
      if (!hier) continue;
      const level = hier.levels[levelName];
      if (!level?.selection) return { memberUniqueNames: [], type: "INCLUSION" };
      const uniqueNames = (level.selection.members as Array<{ uniqueName?: string }>)
        .map((m) => m.uniqueName)
        .filter((v): v is string => !!v);
      return { memberUniqueNames: uniqueNames, type: level.selection.type };
    }
    return { memberUniqueNames: [], type: "INCLUSION" };
  }

  hasRunnableShape(): boolean {
    if (!this.current) return false;
    // MDX-mode queries (raw expression set via MDXModal.onRun or the AI
    // Query drawer's "Edit in canvas") bypass the queryModel surface — the
    // server runs the raw mdx string and we just need it to be non-empty.
    if (this.current.type === "MDX") {
      return !!this.current.mdx && this.current.mdx.trim().length > 0;
    }
    const q = this.current.queryModel;
    if (!q) return false;
    // QUERYMODEL-mode needs at least one measure (on COLUMNS) AND at least
    // one hierarchy on ROWS or COLUMNS. Running without either produces an
    // ugly server-side MDX error, so we silently short-circuit instead.
    const hasMeasure = q.details.measures.length > 0;
    const hasHierarchy = q.axes.COLUMNS.hierarchies.length > 0 || q.axes.ROWS.hierarchies.length > 0;
    return hasMeasure && hasHierarchy;
  }

  async run(): Promise<void> {
    if (!this.current) return;
    if (!this.hasRunnableShape()) {
      // No measure + hierarchy yet — the canvas renders query.error inline,
      // so we surface the hint there rather than as a toast (query-context
      // problems belong in the result pane, not the notification corner).
      this.error = "Add a measure to Columns and a level to Rows or Columns, then hit Run.";
      this.result = null;
      return;
    }
    /*
     * Cancel any in-flight run before starting a new one. Two flows can
     * race a run() against itself: the markDirty() auto-run that fires
     * on every model mutation (autorun=true by default) AND explicit
     * call sites in QueryCanvas. Without this guard the second run()
     * shadows the first; the backend then sees two queries on the same
     * name and cancels the older one, surfacing 'OlapException: Query
     * cancelled' on what looked to the user like a single click. The
     * AbortError from the cancelled fetch is swallowed below, so the
     * second (winning) run gets a clean slate.
     */
    if (this.running) {
      await this.cancel();
    }
    this.running = true;
    this.error = null;
    this.errorDetail = null;
    this.runningQueryId = null;
    this.runningElapsedMs = 0;
    const startedAt = Date.now();
    this.elapsedTimer = setInterval(() => {
      this.runningElapsedMs = Date.now() - startedAt;
    }, 250);
    try {
      if (this.async) {
        const controller = new AbortController();
        this.abortController = controller;
        try {
          const r = await executeQueryAsync(this.current, {
            signal: controller.signal,
            onQueryId: (id) => { this.runningQueryId = id; },
          });
          this.result = r;
        } catch (err) {
          const name = (err as { name?: string }).name;
          if (name === "AbortError") {
            // User cancelled — leave result untouched, no error banner.
            return;
          }
          throw err;
        }
      } else {
        this.result = await executeQuery(this.current);
      }
      // The backend always returns HTTP 200; engine failures (Mondrian /
      // Calcite UnsupportedTranslation, MDX parse, datasource down, etc.)
      // ride along in QueryResult.error with a null cellset. Surface them
      // as a callout instead of rendering an empty grid.
      //
      // Exception: backend-side cancels (OlapException: Query cancelled
      // / Query canceled) only happen when WE cancelled the prior run
      // because a fresh one arrived (see the guard at the top of run()).
      // Treat it as silent — there's a winning run already in flight
      // (or just finished) and surfacing 'cancelled' would shadow its
      // result with a red banner the user can't act on.
      const embedded = this.result?.error;
      if (embedded && /query\s+cancel(l)?ed/i.test(embedded)) {
        this.result = null;
      } else if (embedded) {
        this.error = friendlyExecuteError(embedded);
        this.errorDetail = embedded;
        this.result = null;
      }
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
      // If the failure was auth-related, register a replay thunk so the
      // session modal can re-run this query after the user signs back in.
      // We don't show the "Query failed" toast in that case — the modal
      // already explains what happened.
      if (isAuthError(this.error)) {
        this.result = null;
        this.unregisterPendingOp?.();
        this.unregisterPendingOp = registerPendingOp(() => {
          this.unregisterPendingOp = null;
          return this.run();
        });
      } else {
        // Inline: the canvas renders `query.error` as a callout — no toast
        // needed (would be redundant with the red banner on the result).
        this.result = { cellset: [], error: this.error } as QueryResult;
      }
    } finally {
      this.running = false;
      this.abortController = null;
      this.runningQueryId = null;
      if (this.elapsedTimer) {
        clearInterval(this.elapsedTimer);
        this.elapsedTimer = null;
      }
    }
  }

  async cancel(): Promise<void> {
    const id = this.runningQueryId;
    if (id) {
      try {
        await cancelQuery(id);
      } catch {
        // best-effort
      }
    }
    this.abortController?.abort();
  }
}

/** Match the `execute 401:`, `execute-async 403:`, `status 401:`, `result 401:`
 *  shapes thrown by the query API when credentials lapse. */
function isAuthError(message: string): boolean {
  return /\b(?:execute|execute-async|status|result)\s+40[13]\b/.test(message);
}

/** True when the engine error message names a backend translation /
 *  internal failure that the user can't fix themselves (Mondrian
 *  UnsupportedTranslation when Calcite refuses the SQL emission,
 *  Calcite SqlValidatorException, RolapEvaluatorException, etc.). For
 *  these, we hide the cryptic detail behind "Show details" and lead
 *  with a contact-support callout. Other errors (MDX parse, bad cube
 *  name, datasource down) get the raw message — they're usually
 *  self-explanatory or fixable. */
function isInternalEngineError(message: string): boolean {
  if (!message) return false;
  return /UnsupportedTranslation|SqlValidatorException|RolapEvaluatorException|InternalError|NullPointerException|CalciteContextException|AssertionError/i.test(
    message,
  );
}

/** Choose the user-facing message for an engine error coming back in
 *  {@link QueryResult.error}. Internal/translation failures get a
 *  contact-support callout; ordinary errors (parse, bad name) keep the
 *  raw message so the user can fix them. */
export function friendlyExecuteError(raw: string): string {
  if (isInternalEngineError(raw)) {
    return "Query failed to run. Please contact support.";
  }
  return raw;
}

export const query = new QueryStore();
