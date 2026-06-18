<script lang="ts">
  import type { WizardState, CubeSchema, DimPick } from "../../lib/types";

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

  /** Look up the human-readable level caption from the schema. Falls back to the level key. */
  function levelCaption(d: DimPick): string {
    try {
      return schema.dimensions[d.dimension]?.hierarchies[d.hierarchy]?.levels[d.level]?.name ?? d.level;
    } catch {
      return d.level;
    }
  }

  /** Strip MDX brackets from a member unique name to produce a readable caption. */
  function memberCaption(uniqueName: string): string {
    return uniqueName.split("].[").pop()?.replace(/[\[\]]/g, "") ?? uniqueName;
  }
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
      <span class="section-title">Measures · {wizState.measures.length}</span>
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
      <span class="section-title">Dimensions · {wizState.dimensions.length}</span>
      <button class="edit-link" onclick={() => onEditStep(3)}>Edit</button>
    </div>
    <div class="section-content">
      {#if wizState.dimensions.length === 0}
        <span class="none">None selected</span>
      {:else}
        <ul class="item-list">
          {#each wizState.dimensions as d (`${d.dimension}|${d.hierarchy}|${d.level}`)}
            <li>{levelCaption(d)}</li>
          {/each}
        </ul>
      {/if}
    </div>
  </div>

  <div class="review-section">
    <div class="section-header">
      <span class="section-title">Filters · {wizState.filters.length}</span>
      <button class="edit-link" onclick={() => onEditStep(4)}>Edit</button>
    </div>
    <div class="section-content">
      {#if wizState.filters.length === 0}
        <span class="none">None</span>
      {:else}
        <ul class="item-list">
          {#each wizState.filters as f (`${f.dimension}|${f.hierarchy}|${f.level}`)}
            <li>
              <span class="filter-path">{levelCaption(f)}</span>:
              {f.members.map(memberCaption).join(", ")}
            </li>
          {/each}
        </ul>
      {/if}
    </div>
  </div>
</div>

<style>
  .step { display: flex; flex-direction: column; gap: var(--space-3); }

  .step-hint { font-size: var(--fs-sm); color: var(--fg-muted); margin: 0; }

  .review-section {
    background: var(--bg-muted);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    padding: var(--space-2) 10px;
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }

  .section-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .section-title {
    font-size: var(--fs-xs);
    font-weight: var(--fw-bold);
    color: var(--fg);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }

  .edit-link {
    font-family: var(--font);
    font-size: var(--fs-xs);
    color: var(--accent);
    background: none;
    border: none;
    cursor: pointer;
    padding: 0;
    text-decoration: underline;
  }

  .edit-link:hover { color: var(--accent-hover); }

  .section-content { font-size: var(--fs-sm); color: var(--fg); }

  .none { color: var(--fg-subtle); font-style: italic; }

  .item-list {
    margin: 0;
    padding: 0 0 0 14px;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .item-list li { font-size: var(--fs-xs); color: var(--fg); }

  .filter-path { font-weight: var(--fw-semibold); }
</style>
