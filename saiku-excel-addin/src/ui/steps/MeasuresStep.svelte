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

  const measures = $derived(Object.values(schema.measures));
</script>

<div class="step">
  <p class="step-hint">Choose the measures to include.</p>

  {#if measures.length === 0}
    <div class="empty">No measures found in this cube.</div>
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
</div>

<style>
  .step { display: flex; flex-direction: column; gap: 8px; flex: 1; min-height: 0; }

  .step-hint { font-size: 13px; color: #605e5c; margin: 0; }

  .empty { font-size: 13px; color: #605e5c; padding: 12px 0; }

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
    gap: 8px;
    padding: 6px 8px;
    border-radius: 2px;
    cursor: pointer;
    font-size: 13px;
    color: #323130;
  }

  .measure-item:hover { background: #f3f2f1; }

  .measure-name { flex: 1; }

  input[type="checkbox"] {
    width: 14px;
    height: 14px;
    cursor: pointer;
    accent-color: #0078d4;
    flex-shrink: 0;
  }
</style>
