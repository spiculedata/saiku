<!--
  Active-set picks panel — pinned at the top of the joins panel. One panel,
  two modes: pick mode ("Picked columns (N)" + "Match N picks") and auto mode
  ("Matching <col> (N)" + "Link N similar matches"). Self-contained: reads the
  store's active-set + highlight state and owns the transient group-name input
  + the link-similar count.
-->
<script lang="ts">
	import { ArrowLeftRight, X } from 'lucide-svelte';
	import type { SchemaCanvasStore } from './state.svelte.js';

	interface Props {
		store: SchemaCanvasStore;
	}

	let { store }: Props = $props();

	/** Group name typed into the picks-panel input before Match.  Lives
	 *  in the view (not the store) because it's transient and shouldn't
	 *  persist across sessions or follow connection switches.  Prefilled
	 *  from `store.autoPickGroupName()` whenever the user hasn't edited
	 *  the input themselves — that way Enter or Match "just works" with
	 *  a sensible default. */
	let pickGroupName = $state('');
	/** Once the user types into the group-name field, stop overwriting
	 *  it with the auto-name.  Reset on commit or Clear. */
	let userTouchedName = $state(false);

	const autoName = $derived(store.autoPickGroupName());
	$effect(() => {
		if (!userTouchedName) {
			pickGroupName = autoName;
		}
	});

	function commitPicks() {
		// Empty string falls back to the auto-name inside commitEShiftPicks
		// via the caller — we send whatever's in the input (which we've
		// already synced to autoName unless the user typed).
		store.commitEShiftPicks(pickGroupName || autoName);
		pickGroupName = '';
		userTouchedName = false;
	}
</script>

{#if store.activeSet.length > 0 && store.activeSetMode !== 'none'}
	{@const set = store.activeSet}
	{@const mode = store.activeSetMode}
	{@const colname = store.highlightedColumn}
	<div
		class="flex shrink-0 flex-col gap-2 border-b border-border bg-card px-3 py-3"
		data-testid="canvas-active-set-panel"
	>
		<div class="flex items-center justify-between gap-2">
			<span class="text-[10px] font-semibold tracking-wider text-muted-foreground uppercase">
				{#if mode === 'pick'}
					Picked columns ({set.length})
				{:else}
					Matching <span class="font-mono normal-case">{colname}</span> ({set.length})
				{/if}
			</span>
			<button
				type="button"
				onclick={() => {
					store.cancelEShift();
					store.highlightedColumn = null;
					store.highlightOriginTableId = null;
					pickGroupName = '';
					userTouchedName = false;
				}}
				class="rounded text-[11px] font-medium text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
				data-testid="canvas-active-set-clear"
			>
				Clear
			</button>
		</div>
		<!-- Pick mode → X per row toggles via the same
		     pushEShiftPick API.  Auto mode is read-only
		     (clearing via Clear / emptying the search). -->
		<ul
			class="-mx-1 max-h-40 space-y-1 overflow-y-auto px-1 text-[11px]"
			data-testid="canvas-active-set-list"
		>
			{#each set as p (`${p.tableId}:${p.columnName}`)}
				{@const tbl = store.doc.tables.find((t) => t.id === p.tableId)}
				<li
					class="flex items-center justify-between gap-2 rounded border border-border bg-background px-2 py-1.5 text-foreground"
					data-testid="canvas-active-set-row"
				>
					<span class="min-w-0 truncate font-mono">
						<span class="opacity-60">{tbl?.name ?? p.tableId}.</span>{p.columnName}
					</span>
					{#if mode === 'pick'}
						<button
							type="button"
							onclick={() => store.pushEShiftPick(p.tableId, p.columnName)}
							class="inline-flex h-4 w-4 shrink-0 items-center justify-center rounded text-muted-foreground hover:bg-accent hover:text-accent-foreground"
							aria-label="Remove pick"
							title="Remove from picks"
							data-testid="canvas-active-set-remove"
						>
							<X class="h-3 w-3" aria-hidden="true" />
						</button>
					{/if}
				</li>
			{/each}
		</ul>
		{#if mode === 'pick'}
			{@const canMatch = set.length >= 2}
			<label
				class="flex flex-col gap-1 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase"
			>
				Group name
				<input
					type="text"
					bind:value={pickGroupName}
					oninput={() => (userTouchedName = true)}
					onkeydown={(e) => {
						if (e.key === 'Enter' && canMatch) {
							e.preventDefault();
							commitPicks();
						}
					}}
					placeholder={autoName || 'e.g. Product Key'}
					class="h-8 w-full rounded border border-input bg-background px-2 text-xs font-normal text-foreground normal-case placeholder:text-muted-foreground focus:border-ring focus:ring-1 focus:ring-ring focus:outline-none"
					data-testid="canvas-active-set-group-name"
				/>
			</label>
			<button
				type="button"
				onclick={commitPicks}
				disabled={!canMatch}
				class="inline-flex h-8 w-full items-center justify-center gap-1.5 rounded bg-primary px-2 text-xs font-medium text-primary-foreground transition-opacity hover:opacity-90 disabled:opacity-50"
				title={set.length < 2
					? 'Pick at least two columns on different tables'
					: `Wire the first pick to the other ${set.length - 1} under "${pickGroupName || autoName}"`}
				data-testid="canvas-active-set-commit-picks"
			>
				<ArrowLeftRight class="h-3.5 w-3.5" aria-hidden="true" />
				Match {set.length} picks
			</button>
		{/if}
	</div>
{/if}
