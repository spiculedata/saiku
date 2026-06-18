<script lang="ts">
  import { onMount } from "svelte";
  import { listCubes } from "../../lib/discovery";
  import { cubeId } from "../../lib/types";
  import type { Cube } from "../../lib/types";

  const {
    selectedCubeId,
    onSelect,
  }: {
    selectedCubeId: string;
    onSelect: (id: string) => void;
  } = $props();

  let cubes = $state<Cube[]>([]);
  let loading = $state(true);
  let error = $state<string | null>(null);

  onMount(async () => {
    try {
      cubes = await listCubes();
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to load cubes";
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
  .step { display: flex; flex-direction: column; gap: 8px; flex: 1; min-height: 0; }

  .step-hint { font-size: 13px; color: #605e5c; margin: 0; }

  .loading, .empty { font-size: 13px; color: #605e5c; padding: 12px 0; }

  .error {
    font-size: 13px;
    color: #a4262c;
    padding: 6px 8px;
    background: #fde7e9;
    border-radius: 2px;
  }

  .cube-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
    flex: 1;
    min-height: 0;
    overflow-y: auto;
  }

  .cube-item {
    font-family: Segoe UI, system-ui, sans-serif;
    width: 100%;
    text-align: left;
    background: #f3f2f1;
    border: 1px solid transparent;
    border-radius: 4px;
    padding: 8px 10px;
    cursor: pointer;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .cube-item:hover { background: #edebe9; border-color: #8a8886; }

  .cube-item.selected {
    background: #deecf9;
    border-color: #0078d4;
  }

  .cube-caption { font-size: 13px; font-weight: 600; color: #323130; }
  .cube-detail { font-size: 11px; color: #605e5c; }
</style>
