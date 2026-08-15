<script lang="ts">
	/*
	 * Cascading-select filter picker — extracted from DashboardFilterPanel
	 * (saiku#1230; original feature saiku#922). Walks ONE hierarchy level
	 * by level: dropdown 0 lists members at the start level; picking a
	 * member reveals dropdown 1 with only that member's children; and so
	 * on. The deepest concrete pick is emitted via {@link onCommit}.
	 *
	 * Pure cascade math lives in $lib/dashboard/cascadingFilter; this
	 * component owns the per-level member fetch + dropdown rendering.
	 * Each level reuses the SAME /ai/members/search endpoint scoped to
	 * the parent member by client-side prefix filtering (member unique
	 * names embed the parent path) — no new backend surface.
	 *
	 * Parent contract: hand in the cube/dim/hier + start level + depth +
	 * currently-selected cascade chain; the picker emits the resolved
	 * member chain to commit. Parent persists; picker is pure UI + fetch.
	 */
	import { schemaCache } from '$lib/stores/schemaCache.svelte';
	import type { SchemaLike } from '$lib/dashboard/effectiveQuery';
	import { memberDescendsFromAny } from '$lib/dashboard/cascadingFilters';
	import {
		applySelection,
		effectiveDepth,
		emittedMembers,
		parentForLevel,
		selectionsEqual,
		visibleDropdownCount,
		type CascadeSelections
	} from '$lib/dashboard/cascadingFilter';
	import type { CubeRef } from '$lib/api/dashboards';

	interface MemberRowState {
		loading: boolean;
		error: string | null;
		members: { uniqueName: string; caption: string }[] | null;
		key: string;
	}

	interface Props {
		cube: CubeRef;
		dimension: string;
		hierarchy: string;
		/** Optional explicit cascade start level. Empty → falls back to
		 *  {@link defaultStartLevel} (typically the filter's own level). */
		startLevel: string;
		/** Fallback when {@link startLevel} is empty / not configured. */
		defaultStartLevel: string;
		/** Number of dropdowns to render; bounded by remaining hierarchy levels. */
		depth: number;
		/** Current cascade selection chain (index 0 = start level). */
		selections: CascadeSelections;
		readOnly?: boolean;
		/** Called when the user changes any level; receives the new chain
		 *  and the resolved member list to commit to the underlying filter
		 *  (single deepest concrete pick, or empty for "All"). */
		onCommit: (nextSelections: CascadeSelections, members: string[]) => void;
	}

	let {
		cube,
		dimension,
		hierarchy,
		startLevel,
		defaultStartLevel,
		depth,
		selections,
		readOnly = false,
		onCommit
	}: Props = $props();

	const cubeId = $derived(`${cube.connectionName}/${cube.catalog}/${cube.schema}/${cube.cubeName}`);

	/** Ordered cascade level names, root-most first, starting at the
	 *  configured start level (or the filter's own level) and running
	 *  down the hierarchy. Empty when the schema isn't loaded yet. */
	const levels = $derived.by((): string[] => {
		void schemaCache.version;
		const schema = schemaCache.peek(cube) as SchemaLike | null;
		if (!schema?.dimensions) return [];
		const d = schema.dimensions[dimension.toLowerCase()];
		const h = d?.hierarchies?.[hierarchy.toLowerCase()];
		if (!h?.levels) return [];
		const allLevels = Object.values(h.levels).map((l) => l.name);
		const start = startLevel || defaultStartLevel;
		const startIdx = allLevels.findIndex((n) => n.toLowerCase() === start.toLowerCase());
		if (startIdx < 0) return [];
		return allLevels.slice(startIdx);
	});

	const boundedDepth = $derived(effectiveDepth(depth, levels.length));
	const visible = $derived(visibleDropdownCount(selections, boundedDepth));

	/** Per-level fetched member catalogues, keyed so a parent change
	 *  re-fetches. */
	let cascadeMembers = $state<Record<string, MemberRowState>>({});

	function memberKey(levelIdx: number, parent: string | null): string {
		return `${levelIdx}|${parent ?? ''}`;
	}

	$effect(() => {
		void schemaCache.get(cube).catch(() => {});
		if (boundedDepth <= 0) return;
		for (let i = 0; i < visible; i++) {
			const parent = i === 0 ? null : parentForLevel(selections, i - 1);
			if (i > 0 && parent == null) continue; // parent is "All" — nothing to fetch
			const key = memberKey(i, parent);
			const existing = cascadeMembers[key];
			if (existing && existing.key === key) continue;
			void loadLevel(levels[i], i, parent, key);
		}
	});

	async function loadLevel(
		levelName: string,
		levelIdx: number,
		parent: string | null,
		key: string
	): Promise<void> {
		cascadeMembers = {
			...cascadeMembers,
			[key]: { loading: true, error: null, members: null, key }
		};
		try {
			const params = new URLSearchParams({
				cubeId,
				dimension,
				hierarchy,
				level: levelName,
				limit: '500'
			});
			const res = await fetch(`/rest/saiku/api/ai/members/search?${params.toString()}`, {
				credentials: 'include',
				headers: { Accept: 'application/json' }
			});
			if (!res.ok) throw new Error(`HTTP ${res.status}`);
			let hits = (await res.json()) as {
				uniqueName: string;
				caption: string;
			}[];
			if (parent) {
				hits = hits.filter((m) => memberDescendsFromAny(m.uniqueName, [parent]));
			}
			cascadeMembers = {
				...cascadeMembers,
				[key]: { loading: false, error: null, members: hits, key }
			};
		} catch (e: unknown) {
			const msg = e instanceof Error ? e.message : String(e);
			cascadeMembers = {
				...cascadeMembers,
				[key]: { loading: false, error: msg, members: [], key }
			};
		}
	}

	function membersFor(levelIdx: number): MemberRowState | undefined {
		const parent = levelIdx === 0 ? null : parentForLevel(selections, levelIdx - 1);
		return cascadeMembers[memberKey(levelIdx, parent)];
	}

	function handleChange(levelIdx: number, e: Event): void {
		const raw = (e.target as HTMLSelectElement).value;
		const value = raw === '' ? null : raw; // "" option = "All"
		const next = applySelection(selections, levelIdx, value);
		if (selectionsEqual(selections, next)) return; // idempotent — no-op
		onCommit(next, emittedMembers(next));
	}
</script>

<span class="cascade">
	{#each Array(visible) as _, i (i)}
		{@const lvlCat = membersFor(i)}
		<select
			class="picker-select"
			value={selections[i] ?? ''}
			disabled={readOnly}
			aria-label={levels[i] ?? `Level ${i + 1}`}
			onchange={(e) => handleChange(i, e)}
		>
			<option value="">— all {levels[i] ?? ''} —</option>
			{#if lvlCat?.members}
				{#each lvlCat.members as m (m.uniqueName)}
					<option value={m.uniqueName}>{m.caption}</option>
				{/each}
			{:else if lvlCat?.loading}
				<option value="" disabled>Loading…</option>
			{:else if lvlCat?.error}
				<option value="" disabled>Error: {lvlCat.error}</option>
			{/if}
		</select>
	{/each}
</span>

<style>
	/* Mirror DashboardFilterPanel's own .cascade + .picker-select styles
     so the picker drops in visually identical to the inline original. */
	.cascade {
		display: inline-flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.25rem;
	}
	.picker-select {
		padding: 0.125rem 0.25rem;
		border: 1px solid var(--border-strong, hsl(var(--border)));
		border-radius: 4px;
		background: hsl(var(--bg));
		font-size: 0.8125rem;
		max-width: 200px;
	}
</style>
