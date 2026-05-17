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
  loadDashboard,
  newDashboard,
  saveDashboard,
  type Dashboard,
  type DashboardTile,
} from "$lib/api/dashboards";

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
   *  or an opened saved file). Resets dirty state. */
  hydrate(d: Dashboard, savedPath: string): void {
    this.current = d;
    this.savedPath = savedPath;
    this.dirty = false;
    this.dirtyCount = 0;
    this.saveError = null;
  }

  /** Persist via DashboardResource. Returns true on success. */
  async save(): Promise<boolean> {
    if (!this.current) return false;
    if (!this.savedPath) {
      this.saveError = "Cannot save: dashboard has no repository path. Prompt the user first.";
      return false;
    }
    this.saving = true;
    this.saveError = null;
    try {
      await saveDashboard(this.savedPath, this.current);
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
  }

  /* ----------------------------- mutations ----------------------------- */

  updateName(name: string): void {
    if (!this.current || this.current.name === name) return;
    this.current = { ...this.current, name };
    this.markDirty();
  }

  /** Append a tile. Tile id must be set by the caller (we never invent
   *  ids in a mutator — keeps the dirty-tracking honest). */
  addTile(tile: DashboardTile): void {
    if (!this.current) return;
    this.current = {
      ...this.current,
      layout: {
        ...this.current.layout,
        tiles: [...this.current.layout.tiles, tile],
      },
    };
    this.markDirty();
  }

  removeTile(id: string): void {
    if (!this.current) return;
    const next = this.current.layout.tiles.filter((t) => t.id !== id);
    if (next.length === this.current.layout.tiles.length) return; // no-op
    this.current = {
      ...this.current,
      layout: { ...this.current.layout, tiles: next },
    };
    this.markDirty();
  }

  /** Replace one tile's properties. Object identity changes so any
   *  `$effect` keyed off the tile re-fires. */
  updateTile(id: string, patch: Partial<DashboardTile>): void {
    if (!this.current) return;
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

  private markDirty(): void {
    this.dirty = true;
    this.dirtyCount++;
  }
}

export const dashboardStore = new DashboardStore();
