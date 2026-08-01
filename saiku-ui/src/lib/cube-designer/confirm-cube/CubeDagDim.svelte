<!--
  Dimension node in the cube DAG.  Header (dim name) + every hierarchy
  + every level inside each hierarchy, all expanded by default.  No
  source table listed — semantic view only.
-->
<script lang="ts">
	import { Handle, Position, type NodeProps } from '@xyflow/svelte';

	interface DimHierLevel {
		id: string;
		name: string;
	}
	interface DimHier {
		id: string;
		name: string;
		levels: DimHierLevel[];
	}
	interface DimData {
		name: string;
		hierarchies: DimHier[];
	}

	let { data }: NodeProps & { data: DimData } = $props();
</script>

<div
	class="max-w-72 min-w-56 rounded-md border bg-card px-3 py-2 shadow-sm"
	style:border-color="hsl(var(--border))"
	data-testid="cube-dag-dim"
>
	<div class="flex items-center gap-2">
		<span
			class="rounded-full border border-primary/40 bg-primary/10 px-1.5 py-0.5 font-mono text-[9px] font-bold tracking-wider text-primary uppercase"
		>
			Dim
		</span>
		<div class="min-w-0 flex-1 truncate font-semibold">{data.name}</div>
	</div>
	{#if data.hierarchies.length > 0}
		<ul class="mt-2 flex flex-col gap-2 pt-1.5" style:border-top="1px solid hsl(var(--border))">
			{#each data.hierarchies as h (h.id)}
				<li>
					<div class="flex items-center gap-1.5 text-[10px] font-semibold tracking-wide">
						<span
							class="rounded px-1 py-0.5 font-mono text-[9px] uppercase"
							style:background-color="hsl(var(--muted))"
							style:color="hsl(var(--muted-foreground))"
						>
							Hier
						</span>
						<span class="min-w-0 truncate">{h.name}</span>
					</div>
					{#if h.levels.length > 0}
						<ol class="mt-0.5 flex flex-col gap-0.5 pl-4 font-mono text-[10px]">
							{#each h.levels as lvl, i (lvl.id)}
								<li class="flex items-center gap-1.5 truncate">
									<span class="w-3 shrink-0 text-right text-muted-foreground opacity-60">
										{i + 1}
									</span>
									<span class="truncate">{lvl.name}</span>
								</li>
							{/each}
						</ol>
					{:else}
						<p class="mt-0.5 pl-4 text-[10px] italic opacity-60">No levels yet</p>
					{/if}
				</li>
			{/each}
		</ul>
	{:else}
		<p
			class="mt-2 pt-1.5 text-[10px] italic opacity-60"
			style:border-top="1px solid hsl(var(--border))"
		>
			No hierarchies yet
		</p>
	{/if}
	<Handle type="target" position={Position.Left} style="opacity: 0; pointer-events: none;" />
</div>
