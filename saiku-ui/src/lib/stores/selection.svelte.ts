import type { SaikuCube } from "$lib/api/discover";

/**
 * Ossie-side selection: the connection name (an OSSIE-typed SaikuConnection) and the
 * semantic-model name inside its YAML. One-model-per-datasource is the MVP shape so
 * `modelName` typically equals the connection name; kept separate so we can extend to
 * multi-model YAML files without another store refactor.
 */
export interface OssieSelection {
  connectionName: string;
  modelName: string;
}

/**
 * Snapshot union captured by {@link SelectionStore.snapshot}. Discriminated by the mode
 * field so the tabs store can preserve either flavour across tab switches without losing
 * the other channel's state.
 */
export type SelectionSnapshot =
  | { mode: "olap"; cube: SaikuCube | null }
  | { mode: "ossie"; ossie: OssieSelection | null };

class SelectionStore {
  /** Selected MDX cube. Non-null only in OLAP mode. */
  cube = $state<SaikuCube | null>(null);

  /**
   * Selected Ossie semantic model. Non-null only in Ossie mode. The workbench keys off
   * this to render the Ossie sidebar + shelves instead of the MDX ones.
   */
  ossie = $state<OssieSelection | null>(null);

  /** Convenience discriminator — used by the workspace shell to branch on which sidebar / canvas to render. */
  get mode(): "olap" | "ossie" | "none" {
    if (this.cube) return "olap";
    if (this.ossie) return "ossie";
    return "none";
  }

  select(cube: SaikuCube): void {
    this.cube = cube;
    this.ossie = null;
  }

  selectOssie(sel: OssieSelection): void {
    this.ossie = sel;
    this.cube = null;
  }

  clear(): void {
    this.cube = null;
    this.ossie = null;
  }

  /** Capture the current selection so the tabs store can preserve it across tab switches. */
  snapshot(): SelectionSnapshot {
    if (this.ossie) return { mode: "ossie", ossie: this.ossie };
    return { mode: "olap", cube: this.cube };
  }

  /** Restore a previously-captured selection. Called by the tabs store on tab switch. */
  restore(snap: SelectionSnapshot | SaikuCube | null): void {
    if (snap === null) {
      this.clear();
      return;
    }
    // Backwards-compat: earlier snapshots stored a bare SaikuCube (or null). Detect via the
    // absence of the discriminator and treat as an OLAP snapshot.
    if (typeof (snap as SelectionSnapshot).mode !== "string") {
      this.cube = snap as SaikuCube;
      this.ossie = null;
      return;
    }
    const s = snap as SelectionSnapshot;
    if (s.mode === "ossie") {
      this.ossie = s.ossie;
      this.cube = null;
    } else {
      this.cube = s.cube;
      this.ossie = null;
    }
  }
}

export const selection = new SelectionStore();
