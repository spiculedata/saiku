import type { SaikuCube } from "$lib/api/discover";

class SelectionStore {
  cube = $state<SaikuCube | null>(null);

  select(cube: SaikuCube): void {
    this.cube = cube;
  }

  clear(): void {
    this.cube = null;
  }

  /** Capture the selected cube so the tabs store can preserve it across
   *  tab switches alongside the query snapshot. */
  snapshot(): SaikuCube | null {
    return this.cube;
  }

  /** Restore a previously-captured cube selection (or null for "no
   *  selection"). Called by the tabs store on tab switch. */
  restore(cube: SaikuCube | null): void {
    this.cube = cube;
  }
}

export const selection = new SelectionStore();
