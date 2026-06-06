/*
 * Dashboard store — the loaded Dashboard plus load / save / dirty state.
 *
 * Singleton, same pattern as $lib/stores/query.svelte.ts. The route opens
 * a dashboard via `dashboardStore.load(path)`; mutations (rename, tile
 * add/remove/move/update) flip `dirty` so the toolbar's save button knows
 * there's pending work.
 *
 * Tile-level mutations are immutable per the project coding-style rules
 * — we replace tile arrays with new arrays rather than splicing in place,
 * which keeps Svelte 5's reactivity behaving and avoids the
 * state_referenced_locally trap on derived arrays.
 */

import {
  cloneTileWithFreshId,
  loadDashboard,
  newDashboard,
  normaliseDashboardPath,
  saveDashboard,
  type Dashboard,
  type DashboardFilter,
  type DashboardFilterPanel,
  type DashboardTile,
  type FilterWidget,
  type PanelFilter,
} from "$lib/api/dashboards";
import {
  restoreMembersToDefaults,
  snapshotPanelDefaults,
  type SavedDefaultMembers,
} from "$lib/dashboard/filterDefaults";
import { HistoryStack } from "$lib/dashboard/history";
import { firstFreeSlot } from "$lib/dashboard/tilePlacement";
import { recentDashboards } from "$lib/stores/recentDashboards.svelte";
import { session } from "$lib/stores/session.svelte";

class DashboardStore {
  /** The active dashboard. `null` before the first hydrate / load. */
  current = $state<Dashboard | null>(null);

  /** Repository path the dashboard was loaded from / will save to. Empty
   *  string means "unsaved" — the toolbar must prompt for a path before
   *  the first save. */
  savedPath = $state<string>("");

  loading = $state<boolean>(false);
  saving = $state<boolean>(false);
  loadError = $state<string | null>(null);
  saveError = $state<string | null>(null);

  dirty = $state<boolean>(false);
  dirtyCount = $state<number>(0);

  /** Signal that the next Tile to render with this id should auto-open
   *  its editor modal — used by {@link duplicateTile} so the freshly
   *  cloned tile lands in immediate-edit mode (issue #913). Consumed
   *  exactly once via {@link consumeEditSignal}; left unset otherwise. */
  pendingEditTileId = $state<string | null>(null);

  /** Per-panel-widget snapshot of members[] as they were on the
   *  saved-to-disk dashboard. Re-captured on every {@link hydrate} so
   *  {@link resetPanelFiltersToSaved} and the toolbar's "can reset?"
   *  check can compare without a network round-trip. (Issue #927.) */
  savedDefaultMembers = $state<SavedDefaultMembers>({});

  /* ------------------------------ history ------------------------------ */

  /** Undo/redo history of structural edits (issue #914). Snapshot-based:
   *  {@link markDirty} pushes a deep clone of the pre-mutation document so
   *  {@link undo} can restore it. Pure stack logic lives in
   *  $lib/dashboard/history; this store owns the cloning + reactive
   *  canUndo/canRedo flags. Reset on every {@link hydrate} (undo does not
   *  cross a load / page reload). Capped at 50 entries. */
  private history = new HistoryStack<Dashboard>(50);

  /** Reactive mirrors of {@link history}'s availability — the toolbar
   *  buttons + keyboard handler read these. Kept in $state so Svelte 5
   *  re-renders when a mutation / undo / redo shifts the stacks (the
   *  HistoryStack getters themselves aren't reactive). */
  canUndo = $state<boolean>(false);
  canRedo = $state<boolean>(false);

  /** Set true only for the duration of an {@link undo} / {@link redo}
   *  apply, so the resulting {@code this.current} reassignment doesn't
   *  record itself back onto the history stack. */
  private applyingHistory = false;

  /* ----------------------------- lifecycle ----------------------------- */

  /** Load a dashboard from the repository. On 404 the store falls back to
   *  a fresh empty dashboard so the editor can offer "save to create". */
  async load(path: string): Promise<void> {
    this.loading = true;
    this.loadError = null;
    try {
      if (!path) {
        this.hydrate(newDashboard(), "");
        return;
      }
      const d = await loadDashboard(path);
      this.hydrate(d, path);
      // Track on successful view only — 404 fallbacks below shouldn't
      // poison the "recently viewed" section with dead paths (#936).
      recentDashboards.push(path);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      if (msg.includes("-> 404")) {
        this.hydrate(newDashboard(), path);
        this.loadError = `No dashboard at ${path} — starting a new one. Save to persist.`;
      } else {
        this.current = null;
        this.loadError = msg;
      }
    } finally {
      this.loading = false;
    }
  }

  /** Replace the active dashboard wholesale (e.g. from a deep-link hydrate
   *  or an opened saved file). Resets dirty state.
   *
   *  <p>Runs the saiku#996 migration on load: any {@code type:"filter"}
   *  tile is promoted into the unified filter panel and dropped from the
   *  grid, and any legacy {@code dashboard.filters[]} defaults whose
   *  target isn't already on the panel are absorbed too. The dashboard
   *  is left dirty when the migration touched anything so the next save
   *  persists the cleaned shape. */
  hydrate(d: Dashboard, savedPath: string): void {
    const { migrated, changed } = migrateLegacyFilters(d);
    this.current = migrated;
    this.savedPath = savedPath;
    this.dirty = changed;
    this.dirtyCount = changed ? 1 : 0;
    this.saveError = null;
    // Snapshot AFTER migration — analysts who reset want to land on
    // the migrated baseline, not on the pre-migration shape that no
    // longer exists in-memory. (Issue #927.)
    this.savedDefaultMembers = snapshotPanelDefaults(migrated.filterPanel?.filters);
    // Issue #914: a load is a fresh history baseline — undo does not
    // cross a load / page reload. Seed the stack with the loaded doc so
    // the first edit has a "before" to fall back to.
    this.history.reset(structuredClone($state.snapshot(migrated)) as Dashboard);
    this.pendingBefore = null;
    this.syncHistoryFlags();
  }

  /** Persist via DashboardResource. Returns true on success. */
  async save(): Promise<boolean> {
    if (!this.current) return false;
    if (!this.savedPath) {
      this.saveError = "Cannot save: dashboard has no repository path. Prompt the user first.";
      return false;
    }
    // Re-resolve the save path so URL-driven entry (e.g. typing
    // /ui/dashboards/oops.saikudash directly) can't bypass the user-home
    // default and land the file at repo root (issue #945).
    let path: string;
    try {
      path = normaliseDashboardPath(this.savedPath, session.current?.username ?? "");
    } catch (e: unknown) {
      this.saveError = e instanceof Error ? e.message : String(e);
      return false;
    }
    this.saving = true;
    this.saveError = null;
    try {
      // Strip any extras off tile.cube before serialising — earlier
      // builds stored the full AiCubeSummary on the tile (cubeCaption,
      // defaultMeasure, measureCount), and the server's AiCubeRef
      // Jackson model rejects unknown fields with a 400. New tile
      // edits already project down, but loaded dashboards from older
      // saves carry the extras through round-trip.
      const sanitised = sanitiseForSave(this.current);
      await saveDashboard(path, sanitised);
      this.savedPath = path;
      this.dirty = false;
      this.dirtyCount = 0;
      return true;
    } catch (e: unknown) {
      this.saveError = e instanceof Error ? e.message : String(e);
      return false;
    } finally {
      this.saving = false;
    }
  }

  reset(): void {
    this.current = null;
    this.savedPath = "";
    this.dirty = false;
    this.dirtyCount = 0;
    this.loadError = null;
    this.saveError = null;
    this.savedDefaultMembers = {};
    this.pendingBefore = null;
    this.canUndo = false;
    this.canRedo = false;
  }

  /** Restore every panel widget's members[] to its saved-default
   *  state (empty[] for in-session-added widgets). No-op when nothing
   *  would change — the {@link restoreMembersToDefaults} helper
   *  short-circuits via referential identity. Issue #927. */
  resetPanelFiltersToSaved(): void {
    if (!this.current?.filterPanel) return;
    this.recordBefore();
    const current = this.current.filterPanel.filters;
    const next = restoreMembersToDefaults(current, this.savedDefaultMembers);
    if (next === current) return;
    this.current = {
      ...this.current,
      filterPanel: { ...this.current.filterPanel, filters: next },
    };
    this.markDirty();
  }

  /* ----------------------------- mutations ----------------------------- */

  updateName(name: string): void {
    if (!this.current || this.current.name === name) return;
    this.recordBefore();
    this.current = { ...this.current, name };
    this.markDirty();
  }

  /** Replace the dashboard's tag list. Deduped + trimmed; empty list
   *  collapses to undefined so the persisted JSON stays clean. (#935) */
  updateTags(tags: string[]): void {
    if (!this.current) return;
    const cleaned = Array.from(
      new Set(tags.map((t) => t.trim()).filter((t) => t.length > 0)),
    );
    const next = cleaned.length === 0 ? undefined : cleaned;
    const same =
      (this.current.tags ?? []).length === (next ?? []).length &&
      (this.current.tags ?? []).every((t, i) => t === (next ?? [])[i]);
    if (same) return;
    this.recordBefore();
    this.current = { ...this.current, tags: next };
    this.markDirty();
  }

  /** Replace the entire tiles array. Used by repositionTile callers
   *  (the edit modal) that need to commit a tile move AND any cascaded
   *  shifts on neighbours in a single dirty bump. */
  replaceTiles(tiles: DashboardTile[]): void {
    if (!this.current) return;
    this.recordBefore();
    this.current = {
      ...this.current,
      layout: { ...this.current.layout, tiles },
    };
    this.markDirty();
  }

  /** Append a tile. Tile id must be set by the caller (we never invent
   *  ids in a mutator — keeps the dirty-tracking honest). */
  addTile(tile: DashboardTile): void {
    if (!this.current) return;
    this.recordBefore();
    this.current = {
      ...this.current,
      layout: {
        ...this.current.layout,
        tiles: [...this.current.layout.tiles, tile],
      },
    };
    this.markDirty();
  }

  /** Duplicate a tile: mint a fresh id, suffix the title with " (copy)",
   *  drop it at the next free slot (same w×h as the source), and signal
   *  the new tile to auto-open its editor. Returns the new tile id, or
   *  null when the source isn't in the current layout. Issue #913. */
  duplicateTile(id: string): string | null {
    if (!this.current) return null;
    const source = this.current.layout.tiles.find((t) => t.id === id);
    if (!source) return null;
    this.recordBefore();
    const slot = firstFreeSlot(this.current.layout, source.w, source.h);
    const cloned: DashboardTile = {
      ...cloneTileWithFreshId(source),
      x: slot.x,
      y: slot.y,
    };
    this.current = {
      ...this.current,
      layout: {
        ...this.current.layout,
        tiles: [...this.current.layout.tiles, cloned],
      },
    };
    this.pendingEditTileId = cloned.id;
    this.markDirty();
    return cloned.id;
  }

  /** Returns true (and clears the signal) iff this tile id matches the
   *  pending edit signal. Tile.svelte calls this on mount to decide
   *  whether to open its editor modal automatically. Idempotent: a
   *  second call returns false. */
  consumeEditSignal(id: string): boolean {
    if (this.pendingEditTileId === id) {
      this.pendingEditTileId = null;
      return true;
    }
    return false;
  }

  removeTile(id: string): void {
    if (!this.current) return;
    const next = this.current.layout.tiles.filter((t) => t.id !== id);
    if (next.length === this.current.layout.tiles.length) return; // no-op
    this.recordBefore();
    this.current = {
      ...this.current,
      layout: { ...this.current.layout, tiles: next },
    };
    this.markDirty();
  }

  /** Bulk-remove tiles by id in a single dirty bump (issue #915). Ids
   *  not present are ignored; a no-op (none matched) leaves state and
   *  dirty count untouched. */
  removeTiles(ids: Iterable<string>): void {
    if (!this.current) return;
    const drop = new Set(ids);
    if (drop.size === 0) return;
    const next = this.current.layout.tiles.filter((t) => !drop.has(t.id));
    if (next.length === this.current.layout.tiles.length) return; // no-op
    this.current = {
      ...this.current,
      layout: { ...this.current.layout, tiles: next },
    };
    this.markDirty();
  }

  /** Bulk-duplicate tiles by id in a single dirty bump (issue #915).
   *  Each source gets a fresh id, a " (copy)" title suffix, and lands at
   *  the next free slot — slots are reserved as we go so duplicates don't
   *  stack on the same cell. Unlike {@link duplicateTile} this does NOT
   *  set pendingEditTileId (a bulk duplicate shouldn't pop N editors).
   *  Returns the new tile ids in source order. */
  duplicateTiles(ids: Iterable<string>): string[] {
    if (!this.current) return [];
    const want = new Set(ids);
    if (want.size === 0) return [];
    // Preserve layout order for stable, predictable placement.
    const sources = this.current.layout.tiles.filter((t) => want.has(t.id));
    if (sources.length === 0) return [];

    // Reserve slots against a growing working layout so each clone sees
    // the previous clone already occupying its cell.
    let working = this.current.layout;
    const clones: DashboardTile[] = [];
    for (const source of sources) {
      const slot = firstFreeSlot(working, source.w, source.h);
      const cloned: DashboardTile = {
        ...cloneTileWithFreshId(source),
        x: slot.x,
        y: slot.y,
      };
      clones.push(cloned);
      working = { ...working, tiles: [...working.tiles, cloned] };
    }

    this.current = {
      ...this.current,
      layout: {
        ...this.current.layout,
        tiles: [...this.current.layout.tiles, ...clones],
      },
    };
    this.markDirty();
    return clones.map((c) => c.id);
  }

  /** Replace one tile's properties. Object identity changes so any
   *  `$effect` keyed off the tile re-fires. */
  updateTile(id: string, patch: Partial<DashboardTile>): void {
    if (!this.current) return;
    this.recordBefore();
    let changed = false;
    const next = this.current.layout.tiles.map((t) => {
      if (t.id !== id) return t;
      changed = true;
      return { ...t, ...patch };
    });
    if (!changed) return;
    this.current = {
      ...this.current,
      layout: { ...this.current.layout, tiles: next },
    };
    this.markDirty();
  }

  /** Convenience: update position / size in one call. */
  moveTile(id: string, x: number, y: number, w: number, h: number): void {
    this.updateTile(id, { x, y, w, h });
  }

  /* ------------------------- filter panel mutators ---------------------- */

  /** Ensure the dashboard carries a filterPanel (lazy-initialise on first
   *  mutation so a freshly-loaded older dashboard doesn't dirty itself
   *  just by virtue of being touched). */
  private ensurePanel(): DashboardFilterPanel | null {
    if (!this.current) return null;
    const existing = this.current.filterPanel;
    if (existing) return existing;
    const fresh: DashboardFilterPanel = { collapsed: false, filters: [] };
    this.current = { ...this.current, filterPanel: fresh };
    return fresh;
  }

  addPanelFilter(filter: PanelFilter): void {
    this.recordBefore();
    const panel = this.ensurePanel();
    if (!panel || !this.current) return;
    this.current = {
      ...this.current,
      filterPanel: { ...panel, filters: [...panel.filters, filter] },
    };
    this.markDirty();
  }

  removePanelFilter(id: string): void {
    if (!this.current?.filterPanel) return;
    const next = this.current.filterPanel.filters.filter((f) => f.id !== id);
    if (next.length === this.current.filterPanel.filters.length) return;
    this.recordBefore();
    this.current = {
      ...this.current,
      filterPanel: { ...this.current.filterPanel, filters: next },
    };
    this.markDirty();
  }

  /** Update one panel filter — typically to flip {@code members[]} when
   *  the user picks a value in the dropdown. */
  updatePanelFilter(id: string, patch: Partial<PanelFilter>): void {
    if (!this.current?.filterPanel) return;
    this.recordBefore();
    let changed = false;
    const next = this.current.filterPanel.filters.map((f) => {
      if (f.id !== id) return f;
      changed = true;
      return { ...f, ...patch };
    });
    if (!changed) return;
    this.current = {
      ...this.current,
      filterPanel: { ...this.current.filterPanel, filters: next },
    };
    this.markDirty();
  }

  /** Reorder panel filters by supplying the full id sequence. Ids not
   *  present in the new order are appended in their original order (no
   *  silent drops). */
  reorderPanelFilters(orderedIds: string[]): void {
    if (!this.current?.filterPanel) return;
    this.recordBefore();
    const byId = new Map(this.current.filterPanel.filters.map((f) => [f.id, f]));
    const reordered: PanelFilter[] = [];
    const seen = new Set<string>();
    for (const id of orderedIds) {
      const f = byId.get(id);
      if (f && !seen.has(id)) {
        reordered.push(f);
        seen.add(id);
      }
    }
    for (const f of this.current.filterPanel.filters) {
      if (!seen.has(f.id)) reordered.push(f);
    }
    this.current = {
      ...this.current,
      filterPanel: { ...this.current.filterPanel, filters: reordered },
    };
    this.markDirty();
  }

  setPanelCollapsed(collapsed: boolean): void {
    const panel = this.ensurePanel();
    if (!panel || !this.current) return;
    if (panel.collapsed === collapsed) return;
    this.current = {
      ...this.current,
      filterPanel: { ...panel, collapsed },
    };
    this.markDirty();
  }

  /** Deep clone of the document captured at the top of a mutator, before
   *  it reassigns {@code this.current}. Consumed by {@link markDirty} to
   *  push an undo entry. Overwritten (and thus harmlessly discarded) by
   *  the next mutator when the current one early-returns as a no-op. */
  private pendingBefore: Dashboard | null = null;

  /** Capture the pre-mutation document for the history stack. Called as
   *  the first line of every structural mutator. Deep-clones so later
   *  immutable reassignments can't alias into the snapshot. Skipped while
   *  applying an undo/redo so the restore isn't itself recorded. */
  private recordBefore(): void {
    if (this.applyingHistory || !this.current) {
      this.pendingBefore = null;
      return;
    }
    this.pendingBefore = structuredClone($state.snapshot(this.current)) as Dashboard;
  }

  private markDirty(): void {
    this.dirty = true;
    this.dirtyCount++;
    // Record the undo entry now that the mutation has committed. No-op
    // mutators return before reaching here, so only real changes land on
    // the stack. Snapshot `current` too so the entry's "after" baseline
    // matches what redo will reproduce.
    if (!this.applyingHistory && this.pendingBefore && this.current) {
      const after = structuredClone($state.snapshot(this.current)) as Dashboard;
      this.history.push(this.pendingBefore, after);
      this.pendingBefore = null;
      this.syncHistoryFlags();
    }
  }

  private syncHistoryFlags(): void {
    this.canUndo = this.history.canUndo;
    this.canRedo = this.history.canRedo;
  }

  /* ----------------------------- undo / redo --------------------------- */

  /** Restore the document to the state before the most recent structural
   *  edit (issue #914). No-op when there's nothing to undo. Leaves the
   *  dashboard dirty (the in-memory state now differs from disk). */
  undo(): void {
    const restored = this.history.undo();
    if (!restored) return;
    this.applyDocument(restored);
  }

  /** Re-apply the most recently undone structural edit. No-op when
   *  there's nothing to redo. */
  redo(): void {
    const restored = this.history.redo();
    if (!restored) return;
    this.applyDocument(restored);
  }

  /** Swap the live document for a history snapshot without recording the
   *  swap itself. Clones defensively so a future mutation can't alias
   *  back into the entry still held on the stack. */
  private applyDocument(doc: Dashboard): void {
    this.applyingHistory = true;
    try {
      this.current = structuredClone(doc) as Dashboard;
      this.dirty = true;
      this.dirtyCount++;
    } finally {
      this.applyingHistory = false;
    }
    this.syncHistoryFlags();
  }
}

export const dashboardStore = new DashboardStore();

/** Project every tile's {@code cube} down to the 4 fields the server's
 *  AiCubeRef accepts. Defensive — new edits already project on assignment,
 *  but legacy dashboards round-tripped through `loadDashboard` keep
 *  whatever extras were saved in earlier builds. */
function sanitiseForSave(d: Dashboard): Dashboard {
  return {
    ...d,
    layout: {
      ...d.layout,
      tiles: d.layout.tiles.map((t) => (t.cube ? { ...t, cube: pickCubeRef(t.cube) } : t)),
    },
  };
}

/** Promote legacy {@code type:"filter"} tiles + {@code dashboard.filters[]}
 *  defaults into the unified filter panel (saiku#996). Idempotent: a
 *  dashboard that already lives on the new model has {@code changed:false}
 *  so its hydrate doesn't dirty the editor by virtue of being opened.
 *
 *  <p>Behaviour:
 *   - Each filter tile becomes a PanelFilter, panel id = tile id so any
 *     external bookmark referencing the tile id (unlikely but possible)
 *     still resolves.
 *   - Filter tiles are removed from the grid layout — they no longer
 *     occupy real-estate.
 *   - Defaults whose target isn't already on the panel are appended as
 *     single-select pickers, preserving their members[].
 *   - {@code dashboard.filters[]} is emptied after migration; the panel
 *     is the new source of truth for active filter state. */
function migrateLegacyFilters(d: Dashboard): { migrated: Dashboard; changed: boolean } {
  const filterTiles = d.layout.tiles.filter((t) => t.type === "filter");
  const existing = d.filterPanel?.filters ?? [];
  const existingKeys = new Set(existing.map(filterKey));

  const promoted: PanelFilter[] = [];
  for (const tile of filterTiles) {
    if (!tile.target) continue;
    const widget: FilterWidget = (tile.widget as FilterWidget | undefined) ?? "single-select";
    const candidate: PanelFilter = {
      id: tile.id,
      widget,
      cube: tile.cube,
      dimension: tile.target.dimension,
      hierarchy: tile.target.hierarchy,
      level: tile.target.level,
      members: tile.target.members ?? [],
    };
    if (existingKeys.has(filterKey(candidate))) continue;
    existingKeys.add(filterKey(candidate));
    promoted.push(candidate);
  }

  const absorbedDefaults: PanelFilter[] = [];
  for (let i = 0; i < (d.filters ?? []).length; i++) {
    const def = d.filters[i];
    const key = filterKey(def);
    if (existingKeys.has(key)) continue;
    existingKeys.add(key);
    absorbedDefaults.push({
      id: `default-${i}-${key}`,
      widget: "single-select",
      dimension: def.dimension,
      hierarchy: def.hierarchy,
      level: def.level,
      members: def.members ?? [],
    });
  }

  const changed = promoted.length + absorbedDefaults.length > 0 || filterTiles.length > 0;
  if (!changed) return { migrated: d, changed: false };

  const tilesWithoutFilters = d.layout.tiles.filter((t) => t.type !== "filter");
  const migrated: Dashboard = {
    ...d,
    layout: { ...d.layout, tiles: tilesWithoutFilters },
    filters: [],
    filterPanel: {
      collapsed: d.filterPanel?.collapsed ?? false,
      filters: [...existing, ...promoted, ...absorbedDefaults],
    },
  };
  return { migrated, changed: true };
}

function filterKey(f: DashboardFilter | PanelFilter): string {
  return `${f.dimension}/${f.hierarchy}/${f.level}`;
}

function pickCubeRef(c: DashboardTile["cube"]): DashboardTile["cube"] {
  if (!c) return c;
  return {
    connectionName: c.connectionName,
    catalog: c.catalog,
    schema: c.schema,
    cubeName: c.cubeName,
  };
}
