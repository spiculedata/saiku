/*
 * Per-user "recent MDX workbench queries" store (saiku#1106 phase 3).
 *
 * Same per-user localStorage + cap + most-recent-first shape as
 * {@link recentDashboards} / {@link favouriteDashboards}, built on the shared
 * {@link createUserKeyedListStore} — but each entry here is a small JSON
 * record (the MDX text + which cube it targeted), not a bare path string. The
 * underlying store is typed `string`-only (see userKeyedListStore.svelte.ts),
 * so entries are JSON-encoded on the way in and decoded on the way out;
 * anything that fails to parse (corrupt/hand-edited storage) is dropped
 * rather than surfaced.
 *
 * No dedupe: re-running the same MDX later is a legitimate new history event
 * (the ranAt timestamp already makes every push distinct), unlike a
 * recently-viewed dashboard where "already at the front" is the common case.
 */

import {
	createUserKeyedListStore,
	type UserKeyedListStore
} from '$lib/stores/userKeyedListStore.svelte';
import type { SaikuCube } from '$lib/api/discover';

/** Hard cap — beyond this a "history" panel just becomes another list to scroll. */
export const MDX_HISTORY_CAP = 20;

const STORAGE_PREFIX = 'saiku:mdxHistory:';

export interface MdxHistoryEntry {
	mdx: string;
	cube: SaikuCube;
	/** ISO-8601 timestamp of when the query was run. */
	ranAt: string;
}

function isSaikuCube(v: unknown): v is SaikuCube {
	if (!v || typeof v !== 'object') return false;
	const c = v as Record<string, unknown>;
	return (
		typeof c.connection === 'string' &&
		typeof c.catalog === 'string' &&
		typeof c.schema === 'string' &&
		typeof c.name === 'string'
	);
}

function decode(raw: string): MdxHistoryEntry | null {
	try {
		const parsed = JSON.parse(raw) as Partial<MdxHistoryEntry>;
		if (
			parsed &&
			typeof parsed.mdx === 'string' &&
			typeof parsed.ranAt === 'string' &&
			isSaikuCube(parsed.cube)
		) {
			return { mdx: parsed.mdx, cube: parsed.cube, ranAt: parsed.ranAt };
		}
		return null;
	} catch {
		return null;
	}
}

class MdxHistoryStore {
	private readonly store: UserKeyedListStore = createUserKeyedListStore({
		prefix: STORAGE_PREFIX,
		cap: MDX_HISTORY_CAP,
		dedupe: false
	});

	/** All entries for the current user, most-recent first. Empty when
	 *  there's no current user, and silently drops any corrupt entries. */
	all(): MdxHistoryEntry[] {
		const out: MdxHistoryEntry[] = [];
		for (const raw of this.store.all()) {
			const entry = decode(raw);
			if (entry) out.push(entry);
		}
		return out;
	}

	/** Push a run onto the front of the history list. No-op for blank MDX
	 *  or no current user (delegated to the underlying store). */
	push(entry: MdxHistoryEntry): void {
		if (!entry.mdx.trim()) return;
		this.store.addFront(JSON.stringify(entry));
	}

	clear(): void {
		this.store.clear();
	}
}

export const mdxHistory = new MdxHistoryStore();
