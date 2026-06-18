<script lang="ts">
  import type { WizardState, CubeSchema } from "../../lib/types";

  const {
    wizState,
    schema,
    onEditStep,
  }: {
    wizState: WizardState;
    schema: CubeSchema;
    onEditStep: (step: number) => void;
  } = $props();

  const cubeLabel = $derived(schema.cubeName || wizState.cubeId.split("/").pop() || wizState.cubeId);
</script>

<div class="step">
  <p class="step-hint">Review your query before inserting.</p>

  <div class="review-section">
    <div class="section-header">
      <span class="section-title">Cube</span>
      <button class="edit-link" onclick={() => onEditStep(1)}>Edit</button>
    </div>
    <div class="section-content">{cubeLabel}</div>
  </div>

  <div class="review-section">
    <div class="section-header">
      <span class="section-title">Measures ({wizState.measures.length})</span>
      <button class="edit-link" onclick={() => onEditStep(2)}>Edit</button>
    </div>
    <div class="section-content">
      {#if wizState.measures.length === 0}
        <span class="none">None selected</span>
      {:else}
        <ul class="item-list">
          {#each wizState.measures as m (m)}
            <li>{m}</li>
          {/each}
        </ul>
      {/if}
    </div>
  </div>

  <div class="review-section">
    <div class="section-header">
      <span class="section-title">Dimensions ({wizState.dimensions.length})</span>
      <button class="edit-link" onclick={() => onEditStep(3)}>Edit</button>
    </div>
    <div class="section-content">
      {#if wizState.dimensions.length === 0}
        <span class="none">None selected</span>
      {:else}
        <ul class="item-list">
          {#each wizState.dimensions as d (`${d.dimension}|${d.hierarchy}|${d.level}`)}
            <li>{d.dimension} › {d.hierarchy} › {d.level}</li>
          {/each}
        </ul>
      {/if}
    </div>
  </div>

  <div class="review-section">
    <div class="section-header">
      <span class="section-title">Filters ({wizState.filters.length})</span>
      <button class="edit-link" onclick={() => onEditStep(4)}>Edit</button>
    </div>
    <div class="section-content">
      {#if wizState.filters.length === 0}
        <span class="none">None</span>
      {:else}
        <ul class="item-list">
          {#each wizState.filters as f (`${f.dimension}|${f.hierarchy}|${f.level}`)}
            <li>
              <span class="filter-path">{f.dimension} › {f.hierarchy}</span>:
              {f.members.map((m) => m.split("].[").pop()?.replace(/[\[\]]/g, "") ?? m).join(", ")}
            </li>
          {/each}
        </ul>
      {/if}
    </div>
  </div>
</div>

<style>
  .step { display: flex; flex-direction: column; gap: 10px; }

  .step-hint { font-size: 13px; color: #605e5c; margin: 0; }

  .review-section {
    background: #f3f2f1;
    border-radius: 4px;
    padding: 8px 10px;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .section-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .section-title {
    font-size: 12px;
    font-weight: 700;
    color: #323130;
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }

  .edit-link {
    font-family: Segoe UI, system-ui, sans-serif;
    font-size: 12px;
    color: #0078d4;
    background: none;
    border: none;
    cursor: pointer;
    padding: 0;
    text-decoration: underline;
  }

  .edit-link:hover { color: #106ebe; }

  .section-content { font-size: 13px; color: #323130; }

  .none { color: #a19f9d; font-style: italic; }

  .item-list {
    margin: 0;
    padding: 0 0 0 14px;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .item-list li { font-size: 12px; color: #323130; }

  .filter-path { font-weight: 600; }
</style>
