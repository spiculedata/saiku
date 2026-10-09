<script lang="ts">
	/**
	 * SetsPanel — workspace sidebar entry for managing MDX named sets
	 * (saiku#826). Sibling to the Measures/Dimensions panels rendered by
	 * DimensionList: lists the sets defined on the active query, lets the
	 * analyst create/edit/delete them via SetModal, and offers each set as
	 * a drag source for QueryCanvas's ROWS/COLUMNS drop zones.
	 *
	 * Named sets live on `query.current.queryModel.namedSets[]` — the same
	 * client-side-only pattern DimensionList uses for calculated measures
	 * (see onCalculatedSave there). They ride along with the rest of the
	 * queryModel on every `query.run()`; there's no dedicated save/delete
	 * REST call here. The backend Query2Resource endpoints tracked in #824
	 * are a separate, independent surface (persisting named sets against a
	 * saved query outside of a single run) — this panel doesn't depend on
	 * them landing first.
	 */
	import { query } from '$lib/stores/query.svelte';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import SetModal from '$lib/modals/SetModal.svelte';
	import { Layers, Plus } from '@lucide/svelte';
	import type { ThinNamedSet } from '$lib/api/query';

	const sets = $derived<ThinNamedSet[]>(query.current?.queryModel?.namedSets ?? []);

	let modalOpen = $state(false);
	let modalInitial = $state<ThinNamedSet | undefined>(undefined);

	function openAdd(): void {
		modalInitial = undefined;
		modalOpen = true;
	}

	function openEdit(set: ThinNamedSet): void {
		modalInitial = { ...set };
		modalOpen = true;
	}

	function onSave(set: ThinNamedSet): void {
		query.upsertNamedSet(set);
		modalOpen = false;
		toasts.success(i18n.t('toast.setSaved'), set.caption || set.name);
	}

	function onDelete(name: string): void {
		query.removeNamedSet(name);
		toasts.success(i18n.t('toast.setDeleted'), name);
	}

	/** Bracketed MDX reference for this set, e.g. `[Premium Customers]`. Used both
	 *  as the drag payload's uniqueName and anywhere the set is referenced on an axis. */
	function uniqueNameFor(set: ThinNamedSet): string {
		return `[${set.name}]`;
	}

	function onDragStart(e: DragEvent, set: ThinNamedSet): void {
		const payload = { name: set.name, uniqueName: uniqueNameFor(set) };
		e.dataTransfer?.setData('application/x-saiku-namedset', JSON.stringify(payload));
		if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
	}
</script>

{#if query.current?.queryModel}
	<section class="panel">
		<header class="panel__header flex items-center justify-between">
			<span>{i18n.t('panels.sets')}</span>
			<button
				type="button"
				class="panel__action"
				title={i18n.t('panels.newSet')}
				aria-label={i18n.t('panels.newSet')}
				onclick={openAdd}
			>
				<Plus size={14} />
			</button>
		</header>
		<ul class="tree">
			{#each sets as set (set.name)}
				<li class="tree__node">
					<span class="tree__row tree__row--set">
						<button
							type="button"
							class="tree__drag"
							draggable="true"
							title={set.expression}
							ondragstart={(e) => onDragStart(e, set)}
							onclick={() => openEdit(set)}
						>
							<span class="tree__icon" aria-hidden="true"><Layers size={13} /></span>
							<span class="flex-1 overflow-hidden text-ellipsis whitespace-nowrap"
								>{set.caption || set.name}</span
							>
						</button>
						<button
							type="button"
							class="tree__x"
							title={i18n.t('panels.deleteSet')}
							aria-label="{i18n.t('panels.deleteSet')} {set.caption || set.name}"
							onclick={() => onDelete(set.name)}>×</button
						>
					</span>
				</li>
			{/each}
			{#if sets.length === 0}
				<li class="p-2 text-sm text-fg-subtle">{i18n.t('panels.noSets')}</li>
			{/if}
		</ul>
	</section>
{/if}

<SetModal initial={modalInitial} open={modalOpen} {onSave} onCancel={() => (modalOpen = false)} />

<style>
	.panel {
		background: hsl(var(--bg));
		border-top: 1px solid hsl(var(--border));
		padding-top: var(--space-3);
	}
	.panel__header {
		font-size: var(--fs-xs);
		font-weight: var(--weight-semibold);
		text-transform: uppercase;
		letter-spacing: 0.06em;
		color: hsl(var(--fg-muted));
		margin-bottom: var(--space-2);
	}
	.panel__action {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		background: transparent;
		border: 1px solid transparent;
		color: hsl(var(--fg-muted));
		padding: 4px;
		border-radius: 4px;
		cursor: pointer;
	}
	.panel__action:hover {
		background: hsl(var(--bg-subtle));
		color: hsl(var(--fg));
	}
	.tree {
		list-style: none;
		margin: 0;
		padding-left: 0;
	}
	.tree__row {
		display: flex;
		width: 100%;
		align-items: center;
		gap: var(--space-2);
		padding: 2px var(--space-1);
		background: transparent;
		border: 0;
		color: hsl(var(--fg));
		cursor: pointer;
		font: inherit;
		text-align: left;
		border-radius: var(--radius-sm);
	}
	.tree__row:hover {
		background: hsl(var(--bg-subtle));
	}
	.tree__row--set {
		color: hsl(var(--primary));
	}
	.tree__drag {
		flex: 1;
		display: inline-flex;
		align-items: center;
		gap: var(--space-2);
		background: transparent;
		border: 0;
		color: inherit;
		font: inherit;
		cursor: grab;
		text-align: left;
		padding: 0;
		min-width: 0;
	}
	.tree__icon {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 16px;
		color: hsl(var(--fg-subtle));
	}
	.tree__x {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		width: 1.25rem;
		height: 1.25rem;
		border-radius: 50%;
		background: transparent;
		border: 0;
		color: hsl(var(--fg-muted));
		font-size: 1rem;
		line-height: 1;
		cursor: pointer;
		opacity: 0;
		transition: opacity 120ms ease;
	}
	.tree__row--set:hover .tree__x {
		opacity: 1;
	}
	.tree__x:hover {
		background: color-mix(in srgb, hsl(var(--danger)) 18%, transparent);
		color: hsl(var(--danger));
	}
</style>
