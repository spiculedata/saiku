import {
  executeQuery,
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
  dimensionUniqueName: string;
  hierarchyName: string;
  hierarchyUniqueName: string;
  hierarchyCaption: string;
  levelName: string;
  levelCaption: string;
}

class QueryStore {
  current = $state<ThinQuery | null>(null);
  result = $state<QueryResult | null>(null);
  running = $state<boolean>(false);
  error = $state<string | null>(null);
  dirty = $state<boolean>(false);
  savedPath = $state<string | null>(null);

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

  private findAxisForHierarchy(name: string): AxisLocation | null {
    if (!this.current?.queryModel) return null;
    const axes = this.current.queryModel.axes;
    for (const loc of Object.keys(axes) as AxisLocation[]) {
      if (axes[loc].hierarchies.some((h) => h.name === name)) return loc;
    }
    return null;
  }

  includeLevel(axis: AxisLocation, drop: LevelDrop, position = -1): void {
    if (!this.current?.queryModel) return;
    const model = this.current.queryModel;
    const existing = this.findAxisForHierarchy(drop.hierarchyName);

    let hier: ThinHierarchy;
    if (existing) {
      const fromAxis = model.axes[existing];
      const idx = fromAxis.hierarchies.findIndex((h) => h.name === drop.hierarchyName);
      hier = fromAxis.hierarchies[idx];
      fromAxis.hierarchies.splice(idx, 1);
    } else {
      hier = {
        name: drop.hierarchyName,
        uniqueName: drop.hierarchyUniqueName,
        caption: drop.hierarchyCaption,
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
    this.dirty = true;
  }

  removeHierarchy(hierarchyName: string): void {
    if (!this.current?.queryModel) return;
    const loc = this.findAxisForHierarchy(hierarchyName);
    if (!loc) return;
    const axis = this.current.queryModel.axes[loc];
    axis.hierarchies = axis.hierarchies.filter((h) => h.name !== hierarchyName);
    this.dirty = true;
  }

  addMeasure(m: ThinMeasure): void {
    if (!this.current?.queryModel) return;
    const list = this.current.queryModel.details.measures;
    if (list.some((x) => x.uniqueName === m.uniqueName)) return;
    list.push(m);
    this.dirty = true;
  }

  removeMeasure(uniqueName: string): void {
    if (!this.current?.queryModel) return;
    const details = this.current.queryModel.details;
    details.measures = details.measures.filter((m) => m.uniqueName !== uniqueName);
    this.dirty = true;
  }

  swapAxes(): void {
    if (!this.current?.queryModel) return;
    const m = this.current.queryModel;
    const a = m.axes.ROWS;
    const b = m.axes.COLUMNS;
    m.axes.ROWS = { ...b, location: "ROWS" };
    m.axes.COLUMNS = { ...a, location: "COLUMNS" };
    this.dirty = true;
  }

  setNonEmpty(axis: AxisLocation, nonEmpty: boolean): void {
    if (!this.current?.queryModel) return;
    this.current.queryModel.axes[axis].nonEmpty = nonEmpty;
    this.dirty = true;
  }

  hasRunnableShape(): boolean {
    const q = this.current?.queryModel;
    if (!q) return false;
    return q.details.measures.length > 0 || q.axes.COLUMNS.hierarchies.length > 0 || q.axes.ROWS.hierarchies.length > 0;
  }

  async run(): Promise<void> {
    if (!this.current) return;
    if (!this.hasRunnableShape()) {
      toasts.warning("Nothing to run", "Add a measure or level to an axis first.");
      return;
    }
    this.running = true;
    this.error = null;
    try {
      this.result = await executeQuery(this.current);
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
      toasts.danger("Query failed", this.error);
    } finally {
      this.running = false;
    }
  }
}

export const query = new QueryStore();
