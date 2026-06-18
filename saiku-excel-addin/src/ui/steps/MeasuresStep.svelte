<script lang="ts">
  import type { CubeSchema } from "../../lib/types";

  const {
    schema,
    selected,
    onToggle,
  }: {
    schema: CubeSchema;
    selected: string[];
    onToggle: (name: string) => void;
  } = $props();

  let search = $state("");
  const allMeasures = $derived(Object.values(schema.measures));
  const measures = $derived(
    search.trim()
      ? allMeasures.filter((m) => m.name.toLowerCase().includes(search.toLowerCase()))
      : allMeasures
  );
</script>

<div class="step">
  <p class="step-hint">Choose the measures to include.</p>

  {#if allMeasures.length === 0}
    <div class="empty">No measures found in this cube.</div>
  {:else}
    <input
      class="search"
      type="search"
      placeholder="Search measures…"
      bind:value={search}
    />
    {#if measures.length === 0}
      <div class="empty">No measures match your search.</div>
    {:else}
      <ul class="measure-list">
        {#each measures as m (m.uniqueName)}
          <li>
            <label class="measure-item">
              <input
                type="checkbox"
                checked={selected.includes(m.name)}
                onchange={() => onToggle(m.name)}
              />
              <span class="measure-name">{m.name}</span>
            </label>
          </li>
        {/each}
      </ul>
    {/if}
  {/if}
</div>

<style>
  .step { display: flex; flex-direction: column; gap: var(--space-2); flex: 1; min-height: 0; }

  .step-hint { font-size: var(--fs-sm); color: var(--fg-muted); margin: 0; }

  .empty { font-size: var(--fs-sm); color: var(--fg-muted); padding: var(--space-3) 0; }

  .search {
    padding: 6px 8px;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    font-size: var(--fs-sm);
    color: var(--fg);
    font-family: var(--font);
  }

  .search:focus {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-soft);
  }

  .measure-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
    min-height: 0;
    overflow-y: auto;
  }

  .measure-item {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: 6px 8px;
    border-radius: var(--radius-sm);
    cursor: pointer;
    font-size: var(--fs-sm);
    color: var(--fg);
  }

  .measure-item:hover { background: var(--bg-muted); }

  .measure-name { flex: 1; }

  input[type="checkbox"] {
    width: 14px;
    height: 14px;
    cursor: pointer;
    accent-color: var(--accent);
    flex-shrink: 0;
  }
</style>
