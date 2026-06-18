<script lang="ts">
  import type { CubeSchema, DimPick } from "../../lib/types";
  import { buildDimensionTree } from "../../lib/dimensionsModel";

  const { schema, selected, onToggle }: {
    schema: CubeSchema; selected: DimPick[]; onToggle: (pick: DimPick) => void;
  } = $props();

  let search = $state("");
  let collapsed = $state<Record<string, boolean>>({});
  const groups = $derived(buildDimensionTree(schema, search));

  const isSelected = (p: DimPick) =>
    selected.some((d) => d.dimension === p.dimension && d.hierarchy === p.hierarchy && d.level === p.level);
  const toggleGroup = (dim: string) => { collapsed = { ...collapsed, [dim]: !collapsed[dim] }; };
</script>

<div class="step">
  <p class="step-hint">Pick the levels to break the data down by.</p>
  <input class="search" type="search" placeholder="Search levels…" bind:value={search} />

  {#if groups.length === 0}
    <div class="empty">{search ? "No levels match your search." : "No dimensions found in this cube."}</div>
  {:else}
    <div class="tree">
      {#each groups as g (g.dimension)}
        <div class="dim-group">
          <button class="dim-name" onclick={() => toggleGroup(g.dimension)} aria-expanded={!collapsed[g.dimension]}>
            <span class="chev" class:open={!collapsed[g.dimension]}>▸</span> {g.dimension}
          </button>
          {#if !collapsed[g.dimension]}
            {#each g.levels as lvl (lvl.hierarchy + lvl.level)}
              <label class="level">
                <input type="checkbox" checked={isSelected(lvl)} onchange={() => onToggle({ dimension: lvl.dimension, hierarchy: lvl.hierarchy, level: lvl.level })} />
                <span class="level-label">{lvl.caption}</span>
                {#if g.showHierarchy}<span class="hier">{lvl.hierarchy}</span>{/if}
              </label>
            {/each}
          {/if}
        </div>
      {/each}
    </div>
  {/if}
</div>

<style>
  .step { display: flex; flex-direction: column; gap: var(--space-2); flex: 1; min-height: 0; }
  .step-hint { font-size: var(--fs-sm); color: var(--fg-muted); margin: 0; }
  .search { padding: 6px 8px; border: 1px solid var(--border); border-radius: var(--radius-sm);
            font-size: var(--fs-sm); color: var(--fg); }
  .search:focus { outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
  .empty { font-size: var(--fs-sm); color: var(--fg-muted); padding: var(--space-3) 0; }
  .tree { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: var(--space-1); }
  .dim-name { display: flex; align-items: center; gap: 6px; background: none; border: none; padding: 4px 0;
              font-weight: var(--fw-semibold); color: var(--fg); font-size: var(--fs-sm); cursor: pointer; text-align: left; }
  .chev { display: inline-block; transition: transform .12s; color: var(--fg-subtle); }
  .chev.open { transform: rotate(90deg); }
  .level { display: flex; align-items: center; gap: 7px; padding: 3px 0 3px 18px; color: var(--fg); cursor: pointer; }
  .level input { accent-color: var(--accent); }
  .level-label { font-size: var(--fs-sm); }
  .hier { margin-left: auto; font-size: var(--fs-xs); color: var(--fg-subtle); text-transform: uppercase; letter-spacing: .03em; }
</style>
