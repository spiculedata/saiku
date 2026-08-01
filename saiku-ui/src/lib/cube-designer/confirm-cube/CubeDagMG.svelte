<!--
  Measure Group node in the cube DAG.  Header + inline list of the
  MG's measure columns.  Handles on both sides so fact→MG edges
  come in from the left and MG→dim edges leave from the right.
-->
<script lang="ts">
	import { Handle, Position, type NodeProps } from '@xyflow/svelte';

	interface MGData {
		name: string;
		measures: string[];
	}

	let { data }: NodeProps & { data: MGData } = $props();
</script>

<div
	class="max-w-64 min-w-48 rounded-md border bg-card px-3 py-2 shadow-sm"
	style:border-color="hsl(var(--border))"
	data-testid="cube-dag-mg"
>
	<div class="flex items-center gap-2">
		<span
			class="rounded-full border border-primary/40 bg-primary/10 px-1.5 py-0.5 font-mono text-[9px] font-bold tracking-wider text-primary uppercase"
		>
			MG
		</span>
		<div class="min-w-0 flex-1 truncate font-semibold">{data.name}</div>
	</div>
	{#if data.measures.length > 0}
		<ul
			class="mt-2 flex flex-col gap-0.5 pt-1.5 pl-1 font-mono text-[10px]"
			style:border-top="1px solid hsl(var(--border))"
			style:color="hsl(var(--muted-foreground))"
		>
			{#each data.measures as m (m)}
				<li class="truncate">
					<span class="opacity-50">Σ</span>
					{m}
				</li>
			{/each}
		</ul>
	{:else}
		<p
			class="mt-2 pt-1.5 text-[10px] italic opacity-60"
			style:border-top="1px solid hsl(var(--border))"
		>
			No measures picked
		</p>
	{/if}
	<Handle type="target" position={Position.Left} style="opacity: 0; pointer-events: none;" />
	<Handle type="source" position={Position.Right} style="opacity: 0; pointer-events: none;" />
</div>
