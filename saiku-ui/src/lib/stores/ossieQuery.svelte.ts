import {
  executeOssieQuery,
  fetchOssieModel,
  loadOssieQuery,
  newOssieQueryModel,
  saveOssieQuery,
  type OssieFieldRef,
  type OssieFilterExpr,
  type OssieMetricRef,
  type OssieModel,
  type OssieQueryModel,
  type OssieQueryResult,
  type OssieSortRef,
  type SavedOssieQuery,
} from "$lib/api/ossie";
import type { QueryResult } from "$lib/api/query";
import type { ChartOptions, ChartType } from "$lib/views/chartTypes";
import { DEFAULT_CHART_OPTIONS } from "$lib/views/chartTypes";

/**
 * One entry on the Ossie undo/redo stack. Mirrors the MDX `HistoryEntry` shape but
 * carries Ossie-specific state (shelf model + savedPath/savedName). No result field —
 * undo triggers a re-run rather than restoring a stale result envelope.
 */
export interface OssieHistoryEntry {
  current: OssieQueryModel | null;
  viewMode: "grid" | "chart";
  chartType: ChartType;
  chartOptions: ChartOptions;
  savedPath: string | null;
  savedName: string | null;
}

/**
 * State for the Ossie-flavoured workbench. Owns the currently-loaded semantic model, the
 * user's shelf-state edits, and the last query result. Kept as a single class-instance so
 * it plays nicely with the workspace's tab store (which snapshots + restores state on tab
 * switch).
 *
 * Not extending {@link OssieQueryModel} directly because a few of its fields (rows,
 * columns, etc.) surface through mutation helpers that use immutable-copy semantics —
 * exposing the raw arrays would tempt callers to push directly, which would break Svelte 5
 * reactivity (mutating a $state array doesn't trigger dependent effects reliably in every
 * Svelte 5 minor).
 */
class OssieQueryStore {
  /**
   * Currently-loaded Ossie model. Null until the workbench receives a
   * `selection.selectOssie` and the schema tree fires {@link loadModel}.
   */
  model = $state<OssieModel | null>(null);

  /** True while the discover fetch is in flight. */
  loading = $state(false);

  /** Sticky error surface from either load or execute. */
  error = $state<string | null>(null);

  /** Shelf-state payload. Reset whenever a new model loads. */
  current = $state<OssieQueryModel | null>(null);

  /** True while an execute call is in flight. */
  running = $state(false);

  /** Most recent successful result — kept so the result grid can render across shelf edits. */
  result = $state<OssieQueryResult | null>(null);

  /**
   * Raw {@link QueryResult} envelope the server returned — same shape the MDX side sees.
   * Kept alongside the projected {@link result} so the shared {@code ChartView} component
   * can consume it directly (its {@code parseCellset} helper is what turns the flat
   * `cellset[][]` back into a `{rows, cols, matrix}` projection). Populated by
   * {@link OssieQueryStore.run}.
   */
  rawResult = $state<QueryResult | null>(null);

  /**
   * View mode: whether the canvas shows the grid or the chart. Mirrors the MDX-side
   * `query.viewMode` but with the smaller set of choices the Ossie workbench supports.
   */
  viewMode = $state<"grid" | "chart">("grid");

  /** Chart type when viewMode is 'chart'. Uses the shared MDX ChartType union. */
  chartType = $state<ChartType>("bar");

  /**
   * Chart options — reuses the same {@link ChartOptions} shape as the MDX side so all
   * existing chart-editor features (titles, sort, top-N, palette, ref lines, etc.) work
   * unchanged.
   */
  chartOptions = $state<ChartOptions>({ ...DEFAULT_CHART_OPTIONS });

  // ------------------------------------------------------------------
  // Undo / redo — mirrors the MDX QueryStore's machinery. Same
  // structuredClone($state.snapshot(...)) pattern, same 50-cap, same
  // captureForUndo() first-line contract on every mutator.
  // ------------------------------------------------------------------

  past = $state<OssieHistoryEntry[]>([]);
  future = $state<OssieHistoryEntry[]>([]);
  canUndo = $derived(this.past.length > 0);
  canRedo = $derived(this.future.length > 0);
  private static readonly MAX_HISTORY = 50;

  private snapshotForHistory(): OssieHistoryEntry {
    return {
      current: this.current ? structuredClone($state.snapshot(this.current)) : null,
      viewMode: this.viewMode,
      chartType: this.chartType,
      chartOptions: structuredClone($state.snapshot(this.chartOptions)),
      savedPath: this.savedPath,
      savedName: this.savedName,
    };
  }

  /**
   * Push the CURRENT state onto the undo stack before a mutation. Every public shelf
   * mutator (addRow / removeValue / setFactDataset / cycleSort / setLimit / swapAxes)
   * calls this as its first line so the user can Cmd-Z back to any prior shelf state.
   * Clears the redo stack — any forward mutation invalidates the previously-undone
   * branch.
   */
  captureForUndo(): void {
    this.past.push(this.snapshotForHistory());
    if (this.past.length > OssieQueryStore.MAX_HISTORY) this.past.shift();
    if (this.future.length) this.future = [];
  }

  /**
   * Apply a {@link OssieHistoryEntry} to the live state. Used by undo() / redo(); loads
   * (loadModel / load) clear history rather than walk it. Uses `$state.snapshot` to
   * unwrap Svelte proxies before structuredClone so we don't hit "Proxy could not be
   * cloned" the way the MDX store did prior to saiku#XXX.
   */
  private applyHistory(entry: OssieHistoryEntry): void {
    const plain = $state.snapshot(entry) as OssieHistoryEntry;
    this.current = plain.current ? structuredClone(plain.current) : null;
    this.viewMode = plain.viewMode;
    this.chartType = plain.chartType;
    this.chartOptions = structuredClone(plain.chartOptions);
    this.savedPath = plain.savedPath;
    this.savedName = plain.savedName;
  }

  undo(): void {
    const prev = this.past.pop();
    if (!prev) return;
    // Snapshot current onto the redo stack BEFORE restoring — a subsequent redo brings
    // us back to where undo() was invoked from.
    this.future.push(this.snapshotForHistory());
    this.applyHistory(prev);
    // Auto re-run the restored shelf state so the result grid follows the undo. Skips
    // when the restored state isn't runnable (e.g. undone all the way back to an empty
    // shelf).
    if (this.hasRunnableShape()) void this.run();
  }

  redo(): void {
    const next = this.future.pop();
    if (!next) return;
    this.past.push(this.snapshotForHistory());
    this.applyHistory(next);
    if (this.hasRunnableShape()) void this.run();
  }

  clearHistory(): void {
    this.past = [];
    this.future = [];
  }

  /**
   * Repository path this query is saved to. Null for unsaved / new queries; set on save
   * and on load. Used by the canvas toolbar's Save button to pick between "save-in-place"
   * and "save-as" (open a picker).
   */
  savedPath = $state<string | null>(null);

  /** Name last associated with the file — surfaced in the canvas toolbar and .saiku file. */
  savedName = $state<string | null>(null);

  /**
   * Bookkeeping: last-loaded connection so re-selecting the same one doesn't refetch.
   */
  private loadedConnection: string | null = null;

  /**
   * Load the semantic model for `connection` (via the discover endpoint). Idempotent:
   * calling again with the same connection is a no-op after the first successful load —
   * pass `force: true` to bypass and refetch.
   */
  async loadModel(username: string, connection: string, modelName: string, force = false): Promise<void> {
    if (!force && this.loadedConnection === connection && this.model) return;
    this.loading = true;
    this.error = null;
    try {
      const m = await fetchOssieModel(username, connection);
      this.model = m;
      // Reset shelf state: the previous model's dataset / metric names are no longer valid.
      const seed = newOssieQueryModel(connection, modelName);
      // Pre-populate the fact dataset from the relationship graph so the sidebar
      // dropdown lands on a sensible default (the user can override before dragging).
      const inferred = this.guessFactDataset();
      if (inferred) seed.factDataset = inferred;
      this.current = seed;
      this.result = null;
      this.loadedConnection = connection;
      // A fresh model load starts with an unsaved shelf state — clear the persistence
      // fields so the toolbar's "Save" doesn't overwrite the previously-opened file.
      this.savedPath = null;
      this.savedName = null;
      // Reset undo/redo — the prior shelf state referenced a different model's
      // datasets, so keeping those history entries would let the user Cmd-Z into an
      // invalid state.
      this.clearHistory();
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
      this.model = null;
      this.current = null;
    } finally {
      this.loading = false;
    }
  }

  /**
   * Persist the current shelf state to a `.saiku` file at {@code path}. Set {@code name}
   * so the file carries its display name (surfaced in the RepositoryBrowser). No-op with
   * a null error surface if there's no shelf state to save.
   */
  async save(path: string, name: string): Promise<void> {
    if (!this.current) return;
    this.error = null;
    try {
      await saveOssieQuery(path, name, this.current);
      this.savedPath = path;
      this.savedName = name;
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
      throw e;
    }
  }

  /**
   * Reload a previously-saved Ossie query from the repository. Returns the loaded shape
   * so the workbench shell can drive its selection update (selection.selectOssie with the
   * connection/model the query targets) before this store's state is hydrated.
   *
   * Returns `null` when the file is an MDX-flavoured query — caller falls through to the
   * MDX load path in that case.
   */
  async load(path: string): Promise<SavedOssieQuery | null> {
    this.error = null;
    try {
      const loaded = await loadOssieQuery(path);
      if (!loaded) return null;
      // Model reload is the caller's responsibility (needs username + selection update);
      // here we just hydrate the shelf state so the canvas chips reflect the file
      // contents.
      this.current = loaded.ossieQueryModel;
      this.result = null;
      this.savedPath = path;
      this.savedName = loaded.name;
      return loaded;
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
      throw e;
    }
  }

  /**
   * True when the shelf state has enough content to translate to SQL — at minimum a fact
   * dataset (auto-picked from the first Values-shelf metric's associated dataset when the
   * user hasn't set it explicitly) plus one value or one row/column.
   */
  hasRunnableShape(): boolean {
    const q = this.current;
    if (!q) return false;
    if (!q.factDataset) return false;
    return q.values.length > 0 || q.rows.length > 0 || q.columns.length > 0;
  }

  /**
   * Set the anchor dataset every metric references. When the user drops their first field
   * or metric we auto-pick the fact via `guessFactDataset` but they can override anytime
   * from the sidebar.
   */
  setFactDataset(name: string): void {
    if (!this.current) return;
    this.captureForUndo();
    this.current = { ...this.current, factDataset: name };
  }

  addRow(ref: OssieFieldRef): void {
    if (!this.current) return;
    if (this.current.rows.some((r) => r.dataset === ref.dataset && r.field === ref.field)) return;
    this.captureForUndo();
    this.current = { ...this.current, rows: [...this.current.rows, ref] };
    this.maybeSeedFact(ref.dataset);
  }

  addColumn(ref: OssieFieldRef): void {
    if (!this.current) return;
    if (this.current.columns.some((r) => r.dataset === ref.dataset && r.field === ref.field)) return;
    this.captureForUndo();
    this.current = { ...this.current, columns: [...this.current.columns, ref] };
    this.maybeSeedFact(ref.dataset);
  }

  addValue(ref: OssieMetricRef): void {
    if (!this.current) return;
    if (this.current.values.some((v) => v.metric === ref.metric)) return;
    this.captureForUndo();
    this.current = { ...this.current, values: [...this.current.values, ref] };
    // Fact-dataset auto-pick: the first metric's associated dataset (inferred from any
    // existing row/column entry, else left unset for the user to set explicitly).
    if (!this.current.factDataset && this.current.rows[0]) {
      // Note: this branch bypasses captureForUndo intentionally — a fact-dataset
      // auto-seed is part of the same user action that just captured for the metric add.
      this.current = { ...this.current, factDataset: this.current.rows[0].dataset };
    } else if (!this.current.factDataset && this.current.columns[0]) {
      this.current = { ...this.current, factDataset: this.current.columns[0].dataset };
    }
  }

  addFilter(expr: OssieFilterExpr): void {
    if (!this.current) return;
    this.captureForUndo();
    this.current = { ...this.current, filters: [...this.current.filters, expr] };
    if (expr.dataset) this.maybeSeedFact(expr.dataset);
  }

  removeRow(idx: number): void {
    if (!this.current) return;
    this.captureForUndo();
    this.current = { ...this.current, rows: this.current.rows.filter((_, i) => i !== idx) };
  }

  removeColumn(idx: number): void {
    if (!this.current) return;
    this.captureForUndo();
    this.current = { ...this.current, columns: this.current.columns.filter((_, i) => i !== idx) };
  }

  removeValue(idx: number): void {
    if (!this.current) return;
    this.captureForUndo();
    this.current = { ...this.current, values: this.current.values.filter((_, i) => i !== idx) };
  }

  removeFilter(idx: number): void {
    if (!this.current) return;
    this.captureForUndo();
    this.current = { ...this.current, filters: this.current.filters.filter((_, i) => i !== idx) };
  }

  setSorts(sorts: OssieSortRef[]): void {
    if (!this.current) return;
    this.captureForUndo();
    this.current = { ...this.current, sorts };
  }

  /**
   * Cycle the sort direction for a target column. Called by the result grid's header-click
   * handler. States (per column):
   *   - not sorted → ASC
   *   - ASC       → DESC
   *   - DESC      → not sorted (removed from the sorts list)
   *
   * Single-column sort semantics: setting a new sort replaces any existing sort. Callers
   * that want additive multi-column sort can pass `additive: true` (matches the Shift-click
   * pattern the MDX side uses).
   */
  cycleSort(target: OssieSortRef, additive = false): void {
    if (!this.current) return;
    this.captureForUndo();
    const matches = (a: OssieSortRef, b: OssieSortRef): boolean =>
      a.metric === b.metric && a.dataset === b.dataset && a.field === b.field;
    const existing = this.current.sorts.find((s) => matches(s, target));
    let sorts: OssieSortRef[];
    if (!existing) {
      const seed: OssieSortRef = { ...target, direction: "ASC" };
      sorts = additive ? [...this.current.sorts, seed] : [seed];
    } else if (existing.direction === "ASC") {
      const flipped: OssieSortRef = { ...existing, direction: "DESC" };
      sorts = additive
        ? this.current.sorts.map((s) => (matches(s, target) ? flipped : s))
        : [flipped];
    } else {
      // Third click removes the sort entirely.
      sorts = additive ? this.current.sorts.filter((s) => !matches(s, target)) : [];
    }
    this.current = { ...this.current, sorts };
  }

  /** Set the LIMIT clause on the emitted SQL. Pass null / 0 / negative to remove it. */
  setLimit(limit: number | null | undefined): void {
    if (!this.current) return;
    this.captureForUndo();
    const next: OssieQueryModel = { ...this.current };
    if (limit == null || limit <= 0) {
      delete next.limit;
    } else {
      next.limit = limit;
    }
    this.current = next;
  }

  /**
   * Swap the Rows and Columns shelves. Mirrors the MDX workbench's "Swap axes" button.
   * When the grid renders a crosstab, this flips it 90°.
   */
  swapAxes(): void {
    if (!this.current) return;
    this.captureForUndo();
    this.current = {
      ...this.current,
      rows: this.current.columns,
      columns: this.current.rows,
    };
  }

  /**
   * Execute the current shelf state and store the result. No-op when the shape isn't
   * runnable — the caller (canvas) should key its Run button off `hasRunnableShape` to
   * avoid awkward toasts.
   */
  async run(): Promise<void> {
    if (!this.current) return;
    if (!this.hasRunnableShape()) return;
    this.running = true;
    this.error = null;
    try {
      const name = `ossie-${Date.now().toString(36)}`;
      const both = await executeOssieQuery(name, this.current);
      this.result = both;
      this.rawResult = both.raw ?? null;
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
      this.result = null;
      this.rawResult = null;
    } finally {
      this.running = false;
    }
  }

  /**
   * Wipe every field back to first-load state. Called by the workspace shell on tab
   * change when the destination tab is Ossie-flavoured with no captured state.
   */
  reset(): void {
    this.model = null;
    this.current = null;
    this.result = null;
    this.rawResult = null;
    this.error = null;
    this.running = false;
    this.loading = false;
    this.loadedConnection = null;
    this.savedPath = null;
    this.savedName = null;
    this.clearHistory();
  }

  private maybeSeedFact(candidate: string): void {
    if (!this.current) return;
    if (this.current.factDataset) return;
    // Prefer a smart pick: if the model exposes relationships, the "fact" side is the
    // dataset that appears most often as the `from` end (that's the many-to-one leaf
    // in the semantic-model convention). If our candidate isn't that pick, use the
    // candidate anyway — the user can always override in the sidebar.
    const preferred = this.guessFactDataset();
    this.current = { ...this.current, factDataset: preferred ?? candidate };
  }

  /**
   * Best-effort inference of the "fact" dataset from the loaded semantic model. The
   * dataset that appears most often as the {@code from} end of a relationship is almost
   * always the fact table (dims are joined out via foreign keys pointing at their PKs).
   * Returns null when the model has no relationships (rare — usually flat lookup models).
   */
  private guessFactDataset(): string | null {
    if (!this.model) return null;
    const counts = new Map<string, number>();
    for (const r of this.model.relationships) {
      counts.set(r.from, (counts.get(r.from) ?? 0) + 1);
    }
    if (counts.size === 0) return null;
    let best: string | null = null;
    let bestCount = 0;
    for (const [name, count] of counts) {
      if (count > bestCount) {
        best = name;
        bestCount = count;
      }
    }
    return best;
  }
}

export const ossieQuery = new OssieQueryStore();
