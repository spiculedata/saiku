import {
	listConnections,
	refreshConnections,
	listDimensions,
	listMeasures,
	type SaikuConnection,
	type SaikuCube,
	type SaikuDimension,
	type SaikuMeasure
} from '$lib/api/discover';

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
	private pollTimer: ReturnType<typeof setInterval> | null = null;

	async load(username: string): Promise<void> {
		this.loading = true;
		this.error = null;
		try {
			this.connections = await listConnections(username);
			this.loaded = true;
		} catch (err) {
			this.error = this.silenceAuth(err);
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
			this.error = this.silenceAuth(err);
		} finally {
			this.loading = false;
		}
	}

	/** Map a fetch failure to the error string that should surface in the
	 *  CUBES sidebar — or {@code null} if the failure is 401/403, since the
	 *  global SessionExpiredBanner (#944) already handles auth-resume and
	 *  showing the raw `… -> 401` line under the picker is both redundant
	 *  AND leaks the internal admin REST path to the user. Non-auth errors
	 *  (transient 5xx, network, schema-load failures) still surface here. */
	private silenceAuth(err: unknown): string | null {
		const msg = err instanceof Error ? err.message : String(err);
		return /->\s*40[13]\b/.test(msg) ? null : msg;
	}

	/**
	 * Background poll for late-arriving cubes (saiku-cloud#631).
	 *
	 * Saiku Cloud's engine is cache-first + async-warming
	 * (saiku-cloud#620 / #621): the first /discover may return a
	 * subset of the eventual cube list while warming completes in the
	 * background (typical case: cold-warehouse Snowflake / BigQuery,
	 * where schema-load takes 30-60s after the first request).
	 *
	 * Polls /discover/refresh every {@link intervalMs} (default 3s),
	 * stops after {@link stableTicksRequired} consecutive identical
	 * cube-list fingerprints (default 2) OR after {@link budgetMs}
	 * (default 60s) — whichever first. The poll does NOT clear
	 * metadataCache and does NOT flip `loading` to true on each tick
	 * (no UI flicker); it only replaces `connections` when the list
	 * fingerprint actually changes.
	 *
	 * Opt-in. Non-cloud deploys do not need this — they don't have
	 * the warming model. Cloud deploys should call this from the
	 * top-level Workspace mount after the initial load() resolves.
	 *
	 * Idempotent: if a poll is already running it's cancelled before
	 * a new one starts.
	 */
	startPolling(
		username: string,
		intervalMs: number = 3000,
		budgetMs: number = 60000,
		stableTicksRequired: number = 2
	): void {
		this.stopPolling();
		let lastFingerprint = this.fingerprint(this.connections);
		let stableTicks = 0;
		const startedAt = Date.now();
		this.pollTimer = setInterval(async () => {
			if (Date.now() - startedAt >= budgetMs) {
				this.stopPolling();
				return;
			}
			try {
				const next = await refreshConnections(username);
				const nextFp = this.fingerprint(next);
				if (nextFp === lastFingerprint) {
					stableTicks++;
					if (stableTicks >= stableTicksRequired) {
						this.stopPolling();
					}
					return;
				}
				stableTicks = 0;
				lastFingerprint = nextFp;
				this.connections = next;
				// Don't blast metadataCache — cached cube metadata for
				// cubes that survived the refresh is still valid. New
				// cubes won't have a cache entry yet, so a click-through
				// will fetch their metadata on first selection.
			} catch {
				// Network blip: keep the timer alive — the next tick may
				// succeed. Snowflake cold-start commonly involves a few
				// transient failures before the warehouse resumes.
			}
		}, intervalMs);
	}

	stopPolling(): void {
		if (this.pollTimer !== null) {
			clearInterval(this.pollTimer);
			this.pollTimer = null;
		}
	}

	/**
	 * Order-independent identity fingerprint for the cube list.
	 * Used by `startPolling` to decide whether the list has changed
	 * between two consecutive polls.
	 */
	private fingerprint(connections: SaikuConnection[]): string {
		if (!connections || connections.length === 0) return '';
		const parts: string[] = [];
		for (const conn of connections) {
			for (const cat of conn.catalogs ?? []) {
				for (const sch of cat.schemas ?? []) {
					for (const cube of sch.cubes ?? []) {
						parts.push(`${conn.name}|${cat.name}|${sch.name}|${cube.name}`);
					}
				}
			}
		}
		parts.sort();
		return parts.join('\n');
	}

	/**
	 * Fetch (or return cached) cube metadata.
	 *
	 * @param includeHidden When true, requests the measure tree with
	 *   `?includeHidden=true` (admin-only opt-in, #834). The cache key
	 *   includes this flag so toggling it doesn't return a stale measure
	 *   list to the admin — each variant is cached separately.
	 */
	async metadata(username: string, cube: SaikuCube, includeHidden = false): Promise<CubeMetadata> {
		const key = metadataCacheKey(cube, includeHidden);
		const hit = this.metadataCache.get(key);
		if (hit) return hit;
		const [dimensions, measures] = await Promise.all([
			listDimensions(username, cube),
			listMeasures(username, cube, includeHidden)
		]);
		const md = { dimensions, measures };
		this.metadataCache.set(key, md);
		return md;
	}

	clear(): void {
		this.stopPolling();
		this.connections = [];
		this.metadataCache.clear();
		this.loaded = false;
	}
}

export function cubeKey(cube: SaikuCube): string {
	return `${cube.connection}/${cube.catalog}/${cube.schema}/${cube.name}`;
}

/**
 * Cache key for {@link DatasourceStore.metadata}. Includes the
 * `includeHidden` flag so admins flipping the "Show hidden measures"
 * toggle (#834) get a fresh fetch rather than a stale (visible-only)
 * hit. Exported for the tests in this folder.
 */
export function metadataCacheKey(cube: SaikuCube, includeHidden: boolean): string {
	return `${cubeKey(cube)}#includeHidden=${includeHidden ? '1' : '0'}`;
}

export const datasources = new DatasourceStore();
