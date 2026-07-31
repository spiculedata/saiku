<!--
  Dimension card for the Confirm cube canvas.  Shows the dim name +
  its source table.  Degenerate dims (FactLink) get a subtle badge
  because there's no separate table to point at.
-->
<script lang="ts">
	import { Handle, Position, type NodeProps } from '@xyflow/svelte';

	interface DimCardData {
		dimName: string;
		tableName: string | null;
		tableSubtitle: string | null;
		isDegenerate: boolean;
		fkColumn: string | null;
		primaryKey: string | null;
	}

	let { data }: NodeProps & { data: DimCardData } = $props();
</script>

<div
	class="max-w-56 min-w-44 rounded-md border bg-card px-2.5 py-1.5 text-xs shadow-sm"
	style:border-color="hsl(var(--border))"
	data-testid="confirm-dim-card"
>
	<div class="flex items-center gap-1.5">
		{#if data.isDegenerate}
			<span
				class="rounded-full px-1.5 py-0.5 font-mono text-[9px] font-bold tracking-wider text-primary uppercase"
				style:background-color="hsl(var(--primary) / 0.15)"
				style:border="1px solid hsl(var(--primary) / 0.4)"
				title="Degenerate dimension — Mondrian emits <FactLink> instead of <ForeignKeyLink>."
			>
				FactLink
			</span>
		{:else}
			<span
				class="rounded-full px-1.5 py-0.5 font-mono text-[9px] font-bold tracking-wider uppercase"
				style:background-color="hsl(var(--muted-foreground) / 0.15)"
				style:color="hsl(var(--muted-foreground))"
			>
				Dim
			</span>
		{/if}
		<div class="min-w-0 flex-1 truncate font-medium">{data.dimName}</div>
	</div>
	{#if data.tableName}
		<div
			class="mt-0.5 truncate pl-0.5 font-mono text-[10px]"
			style:color="hsl(var(--muted-foreground))"
		>
			{data.tableSubtitle ?? data.tableName}
		</div>
	{/if}
	{#if !data.isDegenerate && (data.fkColumn || data.primaryKey)}
		<div
			class="mt-1 pt-1 font-mono text-[9px]"
			style:border-top="1px solid hsl(var(--border))"
			style:color="hsl(var(--muted-foreground))"
		>
			{data.fkColumn ?? '?'} <span class="opacity-60">→</span>
			{data.primaryKey ?? '?'}
		</div>
	{/if}
	<Handle type="target" position={Position.Left} style="opacity: 0; pointer-events: none;" />
	<Handle type="target" position={Position.Right} style="opacity: 0; pointer-events: none;" />
</div>
