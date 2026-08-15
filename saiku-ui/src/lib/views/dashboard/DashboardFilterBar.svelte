<script lang="ts">
	/*
	 * Active filter chip bar. Reads from $lib/stores/activeFilters and
	 * renders one chip per entry in `activeFilters.all`. Click-the-X clears
	 * widget + click chips via `clearChip(id)`; defaults are managed via
	 * the dashboardStore (their chips are non-removable here so users
	 * don't accidentally lose a baked-in default).
	 *
	 * The filter chip semantics (precedence indicator, hierarchy collision
	 * warning) land alongside the merge logic in task #12; v0 just shows
	 * the target label.
	 */

	import { activeFilters, type ActiveFilter } from '$lib/stores/activeFilters.svelte';
	import { memberCaption } from '$lib/dashboard/dashboardExport';

	interface Props {
		readOnly?: boolean;
	}

	let { readOnly = false }: Props = $props();

	function chipLabel(af: ActiveFilter): string {
		const f = af.filter;
		// saiku#1762: show the member CAPTION, not its MDX unique name — the chip
		// read "Region: [Geography].[Geography].[West]" while the pill, the
		// dropdown and the page heading all said "West".
		const members = f.members.length === 0 ? '(any)' : f.members.map(memberCaption).join(', ');
		return `${f.level}: ${members}`;
	}

	function chipSourceTag(af: ActiveFilter): string {
		return af.source.kind;
	}

	function isClearable(af: ActiveFilter): boolean {
		// Panel + click sources are both user-managed and clearable from
		// the chip bar; clearing a panel chip just resets its members[] in
		// the persisted dashboard (handled inside activeFilters.clearChip).
		return true;
	}

	function handleClear(af: ActiveFilter): void {
		if (!isClearable(af)) return;
		activeFilters.clearChip(af.id);
	}
</script>

{#if activeFilters.all.length > 0}
	<div class="filter-bar" role="region" aria-label="Active filters">
		{#each activeFilters.all as af (af.id)}
			<span class="chip" data-source={chipSourceTag(af)}>
				{chipLabel(af)}
				{#if !readOnly && isClearable(af)}
					<button
						type="button"
						class="chip-x"
						aria-label="Remove filter"
						onclick={() => handleClear(af)}>×</button
					>
				{/if}
			</span>
		{/each}
	</div>
{/if}

<style>
	/* saiku#1800: the active-filter chips sit on the App page, so they take the
   App's tokens with the global ones as fallback. The default-value chip is a
   tint of the accent -- against a dark global --primary on a light App it was
   pale pink text on white and effectively unreadable. */
	.filter-bar {
		display: flex;
		flex-wrap: wrap;
		gap: 0.375rem;
		padding: 0.25rem 0;
	}
	.chip {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		padding: 0.25rem 0.5rem;
		background: var(--saiku-app-ground, hsl(var(--bg-subtle)));
		border-radius: 999px;
		font-size: 0.8125rem;
		color: var(--saiku-app-fg, hsl(var(--fg)));
	}
	/* Subtle source affordance — click filters look slightly bolder so users
     see which chips came from their interaction vs the dashboard's defaults. */
	.chip[data-source='click'] {
		background: color-mix(in srgb, var(--saiku-app-accent, hsl(var(--primary))) 18%, transparent);
	}
	.chip[data-source='panel'] {
		background: var(--saiku-app-ground, hsl(var(--bg-subtle)));
	}
	.chip-x {
		border: none;
		background: transparent;
		cursor: pointer;
		font-size: 1rem;
		line-height: 1;
		padding: 0 0.125rem;
		color: var(--saiku-app-muted, hsl(var(--fg-muted)));
	}
</style>
