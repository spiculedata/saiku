<script lang="ts">
  import type { CubeSchema, DimPick } from "../../lib/types";

  const {
    schema,
    selected,
    onToggle,
  }: {
    schema: CubeSchema;
    selected: DimPick[];
    onToggle: (pick: DimPick) => void;
  } = $props();

  function isSelected(dimension: string, hierarchy: string, level: string): boolean {
    return selected.some(
      (d) => d.dimension === dimension && d.hierarchy === hierarchy && d.level === level
    );
  }
</script>

<div class="step">
  <p class="step-hint">Choose row dimensions to include.</p>

  {#if Object.keys(schema.dimensions).length === 0}
    <div class="empty">No dimensions found in this cube.</div>
  {:else}
    <ul class="dim-list">
      {#each Object.entries(schema.dimensions) as [dimKey, dim] (dimKey)}
        <li class="dim-group">
          <div class="dim-name">{dimKey}</div>
          {#each Object.entries(dim.hierarchies) as [hierKey, hier] (hierKey)}
            <div class="hier-group">
              <div class="hier-name">{hierKey}</div>
              {#each Object.entries(hier.levels) as [levelKey, level] (levelKey)}
                <label class="level-item">
                  <input
                    type="checkbox"
                    checked={isSelected(dimKey, hierKey, levelKey)}
                    onchange={() => onToggle({ dimension: dimKey, hierarchy: hierKey, level: levelKey })}
                  />
                  <span class="level-label">{level.name}</span>
                  {#if level.cardinality}
                    <span class="level-cardinality">({level.cardinality})</span>
                  {/if}
                </label>
              {/each}
            </div>
          {/each}
        </li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  .step { display: flex; flex-direction: column; gap: 8px; flex: 1; min-height: 0; }

  .step-hint { font-size: 13px; color: #605e5c; margin: 0; }

  .empty { font-size: 13px; color: #605e5c; padding: 12px 0; }

  .dim-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
    flex: 1;
    min-height: 0;
    overflow-y: auto;
  }

  .dim-group {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .dim-name {
    font-size: 12px;
    font-weight: 700;
    color: #0078d4;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    padding: 2px 0;
    border-bottom: 1px solid #edebe9;
  }

  .hier-group {
    padding-left: 8px;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .hier-name {
    font-size: 11px;
    font-weight: 600;
    color: #605e5c;
    margin: 2px 0;
  }

  .level-item {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 4px 6px;
    padding-left: 16px;
    border-radius: 2px;
    cursor: pointer;
    font-size: 13px;
    color: #323130;
  }

  .level-item:hover { background: #f3f2f1; }

  .level-label { flex: 1; }

  .level-cardinality { font-size: 11px; color: #a19f9d; }

  input[type="checkbox"] {
    width: 14px;
    height: 14px;
    cursor: pointer;
    accent-color: #0078d4;
    flex-shrink: 0;
  }
</style>
