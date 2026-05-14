import {
  deleteResource,
  flatten,
  foldersOnly,
  listRepository,
  moveResource,
  type RepositoryNode,
} from "$lib/api/repository";

class RepositoryStore {
  tree = $state<RepositoryNode[]>([]);
  loading = $state<boolean>(false);
  loaded = $state<boolean>(false);
  error = $state<string | null>(null);

  async refresh(): Promise<void> {
    this.loading = true;
    this.error = null;
    try {
      this.tree = await listRepository(["saiku"]);
      this.loaded = true;
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    } finally {
      this.loading = false;
    }
  }

  async remove(path: string): Promise<void> {
    await deleteResource(path);
    await this.refresh();
  }

  async move(source: string, target: string): Promise<void> {
    await moveResource(source, target);
    await this.refresh();
  }

  get flat() {
    return flatten(this.tree);
  }

  get folders() {
    return foldersOnly(this.tree);
  }

  clear() {
    this.tree = [];
    this.loaded = false;
  }
}

export const repository = new RepositoryStore();
