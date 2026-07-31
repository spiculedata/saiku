<!--
  Purple-accented fact-table card for the Confirm cube canvas.  Shows
  the table name + the measure-group names whose fact table this is.
  Handles on both sides so dim edges can attach on whichever side
  the layout puts them.
-->
<script lang="ts">
	import { Handle, Position, type NodeProps } from '@xyflow/svelte';

	interface FactCardData {
		tableName: string;
		tableSubtitle: string | null;
		mgNames: string[];
	}

	let { data }: NodeProps & { data: FactCardData } = $props();
</script>

<!-- Pastel bg via a scoped style block so light + dark render right
     regardless of Tailwind JIT scanning arbitrary values. -->
<div
	class="fact-card max-w-64 min-w-52 rounded-lg border px-3 py-2 text-foreground shadow-sm dark:text-white"
	style:border-color="hsl(275 35% 78%)"
	data-testid="confirm-fact-card"
>
	<div class="flex items-center gap-2">
		<!-- FACT chip: dusty pastel purple + white text.  Text centered
		     via inline-flex + items/justify-center. -->
		<span
			class="inline-flex items-center justify-center rounded-full px-2 py-0.5 font-mono text-[9px] font-bold tracking-wider text-white uppercase"
			style:background-color="hsl(275 30% 55%)"
		>
			Fact
		</span>
		<div class="min-w-0 flex-1 truncate font-mono text-xs font-semibold">
			{data.tableName}
		</div>
	</div>
	{#if data.tableSubtitle}
		<div class="mt-0.5 truncate pl-0.5 font-mono text-[10px] opacity-70">
			{data.tableSubtitle}
		</div>
	{/if}
	{#if data.mgNames.length > 0}
		<div
			class="mt-2 flex flex-wrap gap-1 pt-1.5"
			style:border-top="1px solid hsl(275 40% 45% / 0.5)"
		>
			<span class="text-[9px] font-semibold tracking-wider uppercase opacity-60">MG</span>
			{#each data.mgNames as name (name)}
				<span
					class="rounded px-1.5 py-0.5 text-[10px] font-medium"
					style:background-color="hsl(275 55% 40% / 0.5)"
					style:color="hsl(275 30% 95%)"
				>
					{name}
				</span>
			{/each}
		</div>
	{/if}
	<!-- Handles on both sides so edges can leave in either direction -->
	<Handle type="source" position={Position.Right} style="opacity: 0; pointer-events: none;" />
	<Handle type="source" position={Position.Left} style="opacity: 0; pointer-events: none;" />
	<Handle type="target" position={Position.Right} style="opacity: 0; pointer-events: none;" />
	<Handle type="target" position={Position.Left} style="opacity: 0; pointer-events: none;" />
</div>

<style>
	/* Light default — pale lavender wash.  Dark bg swaps under the SAME
	   rule the app-wide tokens use: explicit [data-theme='dark'] override,
	   OR system dark with no explicit [data-theme='light'] override.
	   Plain `:global(.dark)` doesn't fire in this app — theme uses
	   [data-theme] attrs, not a .dark class. */
	.fact-card {
		background-color: hsl(275 45% 96%);
	}
	:global(:root[data-theme='dark']) .fact-card {
		background-color: hsl(275 35% 22%);
	}
	@media (prefers-color-scheme: dark) {
		:global(:root:not([data-theme='light'])) .fact-card {
			background-color: hsl(275 35% 22%);
		}
	}
</style>
