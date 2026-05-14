import {
  listConnections,
  refreshConnections,
  listDimensions,
  listMeasures,
  type SaikuConnection,
  type SaikuCube,
  type SaikuDimension,
  type SaikuMeasure,
} from "$lib/api/discover";

export interface CubeMetadata {
  dimensions: SaikuDimension[];
  measures: SaikuMeasure[];
}

class DatasourceStore {
  connections = $state<SaikuConnection[]>([]);
  loading = $state<boolean>(false);
  error = $state<string | null>(null);
  loaded = $state<boolean>(false);

  private metadataCache = new Map<string, CubeMetadata>();

  async load(username: string): Promise<void> {
    this.loading = true;
    this.error = null;
    try {
      this.connections = await listConnections(username);
      this.loaded = true;
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    } finally {
      this.loading = false;
    }
  }

  async refresh(username: string): Promise<void> {
    this.loading = true;
    this.error = null;
    try {
      this.connections = await refreshConnections(username);
      this.metadataCache.clear();
      this.loaded = true;
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    } finally {
      this.loading = false;
    }
  }

  async metadata(username: string, cube: SaikuCube): Promise<CubeMetadata> {
    const key = cubeKey(cube);
    const hit = this.metadataCache.get(key);
    if (hit) return hit;
    const [dimensions, measures] = await Promise.all([
      listDimensions(username, cube),
      listMeasures(username, cube),
    ]);
    const md = { dimensions, measures };
    this.metadataCache.set(key, md);
    return md;
  }

  clear(): void {
    this.connections = [];
    this.metadataCache.clear();
    this.loaded = false;
  }
}

export function cubeKey(cube: SaikuCube): string {
  return `${cube.connection}/${cube.catalog}/${cube.schema}/${cube.name}`;
}

export const datasources = new DatasourceStore();
