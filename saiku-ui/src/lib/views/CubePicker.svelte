<script lang="ts">
  import { datasources } from "$lib/stores/datasources.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { session } from "$lib/stores/session.svelte";
  import type { SaikuCube } from "$lib/api/discover";
  import { cubeKey } from "$lib/stores/datasources.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  interface Props {
    username: string;
  }

  let { username }: Props = $props();

  $effect(() => {
    if (username && !datasources.loaded && !datasources.loading && !datasources.error) {
      datasources.load(username);
    }
  });

  let selectedKey = $derived(selection.cube ? cubeKey(selection.cube) : "");

  // Build a flat cube index so the dropdown value can resolve back to the cube object.
  let cubeIndex = $derived(() => {
    const map = new Map<string, SaikuCube>();
    for (const conn of datasources.connections) {
      for (const cat of conn.catalogs) {
        for (const sch of cat.schemas) {
          for (const cube of sch.cubes) {
            map.set(cubeKey(cube), cube);
          }
        }
      }
    }
    return map;
  });

  function onChange(e: Event) {
    const key = (e.currentTarget as HTMLSelectElement).value;
    if (!key) {
      selection.clear();
      return;
    }
    const cube = cubeIndex().get(key);
    if (cube) selection.select(cube);
  }

  async function onRefresh() {
    if (!session.current) return;
    await datasources.refresh(session.current.username);
  }
</script>

<div class="cube-picker">
  <label class="cube-picker__label" for="cubes-select">{i18n.t("cubes.label")}</label>
  <div class="cube-picker__row">
    <select
      id="cubes-select"
      class="cube-picker__select"
      value={selectedKey}
      onchange={onChange}
      disabled={datasources.loading}
    >
      <option value="">
        {datasources.loading ? i18n.t("cubes.loading") : i18n.t("cubes.selectPrompt")}
      </option>
      {#each datasources.connections as conn}
        {#each conn.catalogs as cat}
          {#each cat.schemas as sch}
            {#if sch.cubes.length > 0}
              <optgroup label="{sch.name || cat.name}  ({conn.name})">
                {#each sch.cubes as cube}
                  {#if cube.visible !== false}
                    <option value={cubeKey(cube)}>{cube.caption || cube.name}</option>
                  {/if}
                {/each}
              </optgroup>
            {/if}
          {/each}
        {/each}
      {/each}
    </select>
    <button
      type="button"
      class="btn cube-picker__refresh"
      onclick={onRefresh}
      aria-label={i18n.t("cubes.refresh")}
    >⟳</button>
  </div>
  {#if datasources.error}
    <p class="callout callout--danger">{datasources.error}</p>
  {/if}
  {#if !datasources.loading && datasources.connections.length === 0 && !datasources.error}
    <p class="cube-picker__empty">{i18n.t("cubes.empty")}</p>
  {/if}
</div>

<style>
  .cube-picker {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }
  .cube-picker__label {
    font-size: var(--fs-xs);
    font-weight: var(--weight-semibold);
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--fg-muted);
  }
  .cube-picker__row {
    display: flex;
    gap: var(--space-2);
  }
  .cube-picker__select {
    flex: 1;
    padding: var(--space-2) var(--space-3);
    background: var(--bg);
    color: var(--fg);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    font-size: var(--fs-sm);
  }
  .cube-picker__refresh {
    padding: var(--space-2);
  }
  .cube-picker__empty {
    margin: 0;
    font-size: var(--fs-sm);
    color: var(--fg-subtle);
  }
</style>
