<!--
  Semantic-joins accordion (collapsed by default) — surfaces cube-side links
  (FactLink / ReferenceLink / ForeignKeyLink) that Mondrian 4 expresses inside
  <MeasureGroup><DimensionLinks>, plus legacy inferred-FK joins. Read-only: the
  schema designer creates these from cube authoring, not via the canvas.
-->
<script lang="ts">
	import { ChevronDown } from '@lucide/svelte';
	import type { SchemaCanvasStore } from './state.svelte.js';
	import type { JoinGroupRender } from './join-groups.js';

	interface Props {
		store: SchemaCanvasStore;
		semanticJoinGroups: JoinGroupRender[];
		semanticJoinCount: number;
	}

	let { store, semanticJoinGroups, semanticJoinCount }: Props = $props();

	let semanticSectionExpanded = $state<boolean>(false);
</script>

{#if semanticJoinCount > 0}
	<section
		class="mt-3 rounded border border-dashed border-border"
		data-testid="canvas-joins-panel-semantic"
	>
		<button
			type="button"
			onclick={() => (semanticSectionExpanded = !semanticSectionExpanded)}
			class="flex w-full items-center justify-between gap-2 rounded px-3 py-2 text-left text-[11px] font-medium tracking-wider uppercase transition-colors hover:bg-accent"
			style:color="hsl(var(--muted-foreground))"
			aria-expanded={semanticSectionExpanded}
			data-testid="canvas-joins-panel-semantic-toggle"
		>
			<span class="flex items-center gap-1.5">
				<span
					class="inline-flex h-3.5 w-3.5 items-center justify-center transition-transform"
					style:transform={semanticSectionExpanded ? 'none' : 'rotate(-90deg)'}
				>
					<ChevronDown class="h-3.5 w-3.5" aria-hidden="true" />
				</span>
				Semantic joins ({semanticJoinCount})
			</span>
			<span class="text-[10px] font-normal normal-case opacity-70"> cube-side · read-only </span>
		</button>
		{#if semanticSectionExpanded}
			<div class="space-y-1 border-t border-dashed border-border px-2 py-2">
				{#each semanticJoinGroups as group (group.canonicalKey)}
					<section
						class="rounded border border-dashed border-border/60 bg-muted/30 px-2 py-1.5"
						data-testid="canvas-joins-panel-semantic-group"
						data-group-key={group.canonicalKey}
					>
						<header
							class="text-[11px] font-medium tracking-wide"
							style:color="hsl(var(--muted-foreground))"
						>
							{group.label}
							<span class="ml-1 text-[10px] opacity-60">
								({group.joins.length})
							</span>
						</header>
						<ul class="mt-1 space-y-0.5 font-mono text-[10px] opacity-75">
							{#each group.joins as j (j.id)}
								{@const src = store.doc.tables.find((t) => t.id === j.sourceTableId)}
								{@const tgt = store.doc.tables.find((t) => t.id === j.targetTableId)}
								<li>
									{src?.name ?? '?'}.{j.sourceColumnName} → {tgt?.name ?? '?'}.{j.targetColumnName}
								</li>
							{/each}
						</ul>
					</section>
				{/each}
			</div>
		{/if}
	</section>
{/if}
