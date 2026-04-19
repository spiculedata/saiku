import type { SaikuCube } from "$lib/api/discover";

class SelectionStore {
  cube = $state<SaikuCube | null>(null);

  select(cube: SaikuCube): void {
    this.cube = cube;
  }

  clear(): void {
    this.cube = null;
  }
}

export const selection = new SelectionStore();
