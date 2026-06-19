<script lang="ts">
  import { onMount } from "svelte";
  import { listCubes } from "../../lib/discovery";
  import { cubeId } from "../../lib/types";
  import type { Cube } from "../../lib/types";
  import type { ServerCtx } from "../../lib/http";

  const {
    ctx,
    selectedCubeId,
    onSelect,
  }: {
    ctx: ServerCtx;
    selectedCubeId: string;
    onSelect: (id: string) => void;
  } = $props();

  let cubes = $state<Cube[]>([]);
  let loading = $state(true);
  let error = $state<string | null>(null);

  onMount(async () => {
    try {
      cubes = await listCubes(ctx);
    } catch (e) {
      if (e instanceof TypeError) {
        error = "Can't reach this server, or it hasn't allowed the add-in (CORS)";
      } else {
        error = e instanceof Error ? e.message : "Failed to load cubes";
      }
    } finally {
      loading = false;
    }
  });
</script>

<div class="step">
  <p class="step-hint">Select a cube to query.</p>

  {#if loading}
    <div class="loading">Loading cubes…</div>
  {:else if error}
    <div class="error" role="alert">{error}</div>
  {:else if cubes.length === 0}
    <div class="empty">No cubes available.</div>
  {:else}
    <ul class="cube-list">
      {#each cubes as cube (cubeId(cube))}
        {@const id = cubeId(cube)}
        <li>
          <button
            class="cube-item"
            class:selected={selectedCubeId === id}
            onclick={() => onSelect(id)}
          >
            <span class="cube-caption">{cube.cubeCaption || cube.cubeName}</span>
            <span class="cube-detail">{cube.catalog} / {cube.schema}</span>
          </button>
        </li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  /* Fill the wizard's step-content height so the list uses the whole pane. */
  .step { display: flex; flex-direction: column; gap: var(--space-2); flex: 1; min-height: 0; }

  .step-hint { font-size: var(--fs-sm); color: var(--fg-muted); margin: 0; }

  .loading, .empty { font-size: var(--fs-sm); color: var(--fg-muted); padding: var(--space-3) 0; }

  .error {
    font-size: var(--fs-sm);
    color: var(--danger);
    padding: 6px 8px;
    background: var(--danger-soft);
    border-radius: var(--radius-sm);
  }

  .cube-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    flex: 1;
    min-height: 0;
    overflow-y: auto;
  }

  .cube-item {
    font-family: var(--font);
    width: 100%;
    text-align: left;
    background: var(--bg-muted);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    padding: var(--space-2) 10px;
    cursor: pointer;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .cube-item:hover { background: var(--bg-hover); border-color: var(--border-strong); }

  .cube-item.selected {
    background: var(--accent-soft);
    border-color: var(--accent);
  }

  .cube-caption { font-size: var(--fs-sm); font-weight: var(--fw-semibold); color: var(--fg); }
  .cube-detail { font-size: var(--fs-xs); color: var(--fg-muted); }
</style>
