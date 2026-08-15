/*
 * Pure helpers for the dashboard catalogue's search + filter + sort UX
 * (#935). Filters are *additive* — the catalogue applies all configured
 * predicates in sequence, then sorts the result.
 *
 * Each predicate factory returns a function on an enriched catalogue
 * entry (RepositoryNode + the dashboard's own name/tags loaded
 * separately). Keeping the predicate factories pure makes the
 * compound filter easy to unit-test without spinning up a Svelte
 * harness.
 */

/** Minimal shape the catalogue keeps in memory per entry. The
 *  repository listing supplies most of this; `title` and `tags` come
 *  from the dashboard JSON loaded lazily on catalogue mount. */
export interface CatalogueEntry {
	path: string;
	/** Filename without the `.saikudash` extension. */
	basename: string;
	/** Human-readable dashboard name from the JSON, or `null` while the
	 *  JSON is still loading / failed to load. Search falls back to
	 *  `basename` when this is null. */
	title: string | null;
	/** Tags from the dashboard JSON, empty when none / still loading. */
	tags: string[];
	/** ACL owner, when known. `null` when the server didn't resolve one. */
	owner: string | null;
	/** Last-modified millis since epoch, `0` when unknown. */
	modified: number;
}

/** Build a case-insensitive substring predicate for the search box.
 *  Matches on title (when available) and basename + path. Empty / blank
 *  query matches everything. */
export function searchPredicate(query: string): (e: CatalogueEntry) => boolean {
	const q = query.trim().toLowerCase();
	if (!q) return () => true;
	return (e) => {
		if (e.title && e.title.toLowerCase().includes(q)) return true;
		if (e.basename.toLowerCase().includes(q)) return true;
		if (e.path.toLowerCase().includes(q)) return true;
		return false;
	};
}

/** Build a tag-membership predicate. AND semantics — all selected tags
 *  must appear on the entry. Empty selection matches everything. */
export function tagPredicate(selected: ReadonlyArray<string>): (e: CatalogueEntry) => boolean {
	if (selected.length === 0) return () => true;
	const wanted = new Set(selected);
	return (e) => {
		if (e.tags.length === 0) return false;
		const have = new Set(e.tags);
		for (const t of wanted) {
			if (!have.has(t)) return false;
		}
		return true;
	};
}

/** Build an owner-membership predicate. OR semantics — any selected
 *  owner matches. Empty selection matches everything. Entries with
 *  `owner === null` only match when the special sentinel "(unknown)"
 *  is selected, so an admin can deliberately surface ACL-leaks. */
export const UNKNOWN_OWNER = '(unknown)';

export function ownerPredicate(selected: ReadonlyArray<string>): (e: CatalogueEntry) => boolean {
	if (selected.length === 0) return () => true;
	const wanted = new Set(selected);
	return (e) => {
		if (e.owner == null) return wanted.has(UNKNOWN_OWNER);
		return wanted.has(e.owner);
	};
}

/** Sort options surfaced by the catalogue toolbar's sort-by dropdown. */
export type SortKey = 'name' | 'modified-desc' | 'modified-asc';

/** Build a comparator for the given sort key. Stable ordering is
 *  achieved by name as the tiebreaker when sorting by modified. */
export function sortComparator(key: SortKey): (a: CatalogueEntry, b: CatalogueEntry) => number {
	const byName = (a: CatalogueEntry, b: CatalogueEntry): number => {
		const ax = (a.title ?? a.basename).toLowerCase();
		const bx = (b.title ?? b.basename).toLowerCase();
		return ax.localeCompare(bx);
	};
	switch (key) {
		case 'modified-desc':
			return (a, b) => {
				if (b.modified !== a.modified) return b.modified - a.modified;
				return byName(a, b);
			};
		case 'modified-asc':
			return (a, b) => {
				if (a.modified !== b.modified) return a.modified - b.modified;
				return byName(a, b);
			};
		case 'name':
		default:
			return byName;
	}
}

/** Apply search + tag + owner predicates in series, then sort by the
 *  given key. Returns a new array — does not mutate {@code entries}. */
export function applyCatalogueFilters(
	entries: ReadonlyArray<CatalogueEntry>,
	opts: {
		search?: string;
		tags?: ReadonlyArray<string>;
		owners?: ReadonlyArray<string>;
		sort?: SortKey;
	}
): CatalogueEntry[] {
	const s = searchPredicate(opts.search ?? '');
	const t = tagPredicate(opts.tags ?? []);
	const o = ownerPredicate(opts.owners ?? []);
	const out = entries.filter((e) => s(e) && t(e) && o(e));
	out.sort(sortComparator(opts.sort ?? 'name'));
	return out;
}

/** Collect the union of all tags across the supplied entries, sorted
 *  alphabetically. Used to populate the tag-filter dropdown. */
export function collectTags(entries: ReadonlyArray<CatalogueEntry>): string[] {
	const set = new Set<string>();
	for (const e of entries) {
		for (const t of e.tags) {
			if (t) set.add(t);
		}
	}
	return Array.from(set).sort((a, b) => a.localeCompare(b));
}

/** Collect the union of owners across the supplied entries, sorted
 *  alphabetically. Entries with `owner === null` contribute the
 *  {@link UNKNOWN_OWNER} sentinel. */
export function collectOwners(entries: ReadonlyArray<CatalogueEntry>): string[] {
	const set = new Set<string>();
	let hasUnknown = false;
	for (const e of entries) {
		if (e.owner == null) {
			hasUnknown = true;
		} else if (e.owner) {
			set.add(e.owner);
		}
	}
	const sorted = Array.from(set).sort((a, b) => a.localeCompare(b));
	if (hasUnknown) sorted.push(UNKNOWN_OWNER);
	return sorted;
}
