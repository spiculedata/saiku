<!--
  Fact-table node in the cube DAG.  Compact card — table name +
  qualified subtitle + which MGs use it (as small chips).  Purple
  tinted (matches the earlier confirm cube canvas's fact styling).
-->
<script lang="ts">
	import { Handle, Position, type NodeProps } from '@xyflow/svelte';

	interface FactData {
		tableName: string;
		tableSubtitle: string | null;
		mgNames: string[];
	}

	let { data }: NodeProps & { data: FactData } = $props();
</script>

<!-- Pastel bg via scoped style so light + dark render right
     regardless of Tailwind JIT scanning arbitrary values. -->
<div
	class="cube-dag-fact max-w-64 min-w-52 rounded-md border px-3 py-2 text-foreground shadow-sm dark:text-white"
	style:border-color="hsl(275 35% 78%)"
	data-testid="cube-dag-fact"
>
	<div class="flex items-center gap-2">
		<!-- FACT chip: dusty pastel purple + white text; text centered
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
	<Handle type="source" position={Position.Right} style="opacity: 0; pointer-events: none;" />
</div>

<style>
	/* Light default; dark swaps under the app's [data-theme] rules
	   (see FactCard.svelte for the reasoning). */
	.cube-dag-fact {
		background-color: hsl(275 45% 96%);
	}
	:global(:root[data-theme='dark']) .cube-dag-fact {
		background-color: hsl(275 35% 22%);
	}
	@media (prefers-color-scheme: dark) {
		:global(:root:not([data-theme='light'])) .cube-dag-fact {
			background-color: hsl(275 35% 22%);
		}
	}
</style>
