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
import { toasts } from "$lib/stores/toasts.svelte";

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
  dirty = $state<boolean>(false);
  savedPath = $state<string | null>(null);
  autorun = $state<boolean>(true);
  #async = $state<boolean>(readBoolLS("saiku_async", false));
  runningQueryId = $state<string | null>(null);
  runningElapsedMs = $state<number>(0);

  private abortController: AbortController | null = null;
  private elapsedTimer: ReturnType<typeof setInterval> | null = null;

  get async(): boolean {
    return this.#async;
  }
  set async(value: boolean) {
    this.#async = value;
    writeBoolLS("saiku_async", value);
  }

  private markDirty(): void {
    this.dirty = true;
    if (this.autorun && this.hasRunnableShape()) {
      void this.run();
    }
  }

  initFor(cube: SaikuCube): void {
    this.current = newQuery(cube);
    this.result = null;
    this.error = null;
    this.dirty = false;
    this.savedPath = null;
  }

  loadFromJson(raw: string, path: string): void {
    const parsed = JSON.parse(raw) as ThinQuery;
    this.current = parsed;
    this.result = null;
    this.error = null;
    this.dirty = false;
    this.savedPath = path;
  }

  markSaved(path: string): void {
    this.savedPath = path;
    this.dirty = false;
    if (this.current) this.current.name = path;
  }

  reset(): void {
    this.current = null;
    this.result = null;
    this.error = null;
    this.dirty = false;
    this.savedPath = null;
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

  removeHierarchy(hierarchyName: string): void {
    if (!this.current?.queryModel) return;
    const loc = this.findAxisForHierarchy(hierarchyName);
    if (!loc) return;
    const axis = this.current.queryModel.axes[loc];
    axis.hierarchies = axis.hierarchies.filter((h) => h.name !== hierarchyName);
    this.markDirty();
  }

  addMeasure(m: ThinMeasure): void {
    if (!this.current?.queryModel) return;
    const list = this.current.queryModel.details.measures;
    if (list.some((x) => x.uniqueName === m.uniqueName)) return;
    list.push(m);
    this.markDirty();
  }

  setMeasures(list: ThinMeasure[]): void {
    if (!this.current?.queryModel) return;
    this.current.queryModel.details.measures = [...list];
    this.markDirty();
  }

  removeMeasure(uniqueName: string): void {
    if (!this.current?.queryModel) return;
    const details = this.current.queryModel.details;
    details.measures = details.measures.filter((m) => m.uniqueName !== uniqueName);
    this.markDirty();
  }

  swapAxes(): void {
    if (!this.current?.queryModel) return;
    const m = this.current.queryModel;
    const a = m.axes.ROWS;
    const b = m.axes.COLUMNS;
    m.axes.ROWS = { ...b, location: "ROWS" };
    m.axes.COLUMNS = { ...a, location: "COLUMNS" };
    this.markDirty();
  }

  setNonEmpty(axis: AxisLocation, nonEmpty: boolean): void {
    if (!this.current?.queryModel) return;
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
    const q = this.current?.queryModel;
    if (!q) return false;
    // A runnable shape needs at least one measure (on COLUMNS) AND at least
    // one hierarchy on ROWS or COLUMNS. Running without either produces an
    // ugly server-side MDX error, so we silently short-circuit instead.
    const hasMeasure = q.details.measures.length > 0;
    const hasHierarchy = q.axes.COLUMNS.hierarchies.length > 0 || q.axes.ROWS.hierarchies.length > 0;
    return hasMeasure && hasHierarchy;
  }

  async run(): Promise<void> {
    if (!this.current) return;
    if (!this.hasRunnableShape()) {
      // Silently no-op with a gentle hint — old UI waited for the query to
      // fill in rather than surfacing an ugly server error.
      toasts.info("Add a measure to Columns first", "Drag a measure and a dimension onto the axes before running.");
      return;
    }
    this.running = true;
    this.error = null;
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
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
      this.result = { cellset: [], error: this.error } as QueryResult;
      toasts.danger("Query failed", this.error);
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

export const query = new QueryStore();
