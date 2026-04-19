<script lang="ts">
  import { datasources } from "$lib/stores/datasources.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import type { CubeMetadata } from "$lib/stores/datasources.svelte";
  import type { SaikuCube } from "$lib/api/discover";

  interface Props {
    username: string;
  }

  let { username }: Props = $props();

  let metadata = $state<CubeMetadata | null>(null);
  let loading = $state(false);
  let error = $state<string | null>(null);
  let expanded = $state<Record<string, boolean>>({});
  let cubeSignature = $state<string | null>(null);

  function keyFor(cube: SaikuCube): string {
    return `${cube.connection}/${cube.catalog}/${cube.schema}/${cube.name}`;
  }

  $effect(() => {
    const cube = selection.cube;
    if (!cube) {
      metadata = null;
      cubeSignature = null;
      return;
    }
    const sig = keyFor(cube);
    if (sig === cubeSignature && metadata) return;
    cubeSignature = sig;
    loading = true;
    error = null;
    datasources
      .metadata(username, cube)
      .then((m) => (metadata = m))
      .catch((err: unknown) => {
        error = err instanceof Error ? err.message : String(err);
      })
      .finally(() => (loading = false));
  });

  function toggle(id: string) {
    expanded[id] = !(expanded[id] ?? true);
  }

  function measureGroups(): Record<string, typeof metadata extends null ? never : NonNullable<typeof metadata>["measures"]> {
    const groups: Record<string, NonNullable<typeof metadata>["measures"]> = {};
    if (!metadata) return groups;
    for (const m of metadata.measures) {
      const key = "Measures";
      (groups[key] ??= []).push(m);
    }
    return groups;
  }
</script>

<div class="panels">
  {#if !selection.cube}
    <p class="panels__hint">Select a cube to browse its measures and dimensions.</p>
  {:else if loading}
    <p class="panels__hint">Loading cube metadata…</p>
  {:else if error}
    <p class="callout callout--danger">{error}</p>
  {:else if metadata}
    <section class="panel">
      <header class="panel__header">Measures</header>
      <ul class="tree">
        {#each Object.entries(measureGroups()) as [group, items]}
          {@const gid = `m:${group}`}
          <li class="tree__node">
            <button type="button" class="tree__row tree__row--group" onclick={() => toggle(gid)}>
              <span class="tree__twisty">{expanded[gid] === false ? "▸" : "▾"}</span>
              <span class="tree__label">{group}</span>
              <span class="tree__count">{items.length}</span>
            </button>
            {#if expanded[gid] !== false}
              <ul class="tree">
                {#each items as measure}
                  <li class="tree__node">
                    <button type="button" class="tree__row tree__row--measure" draggable="true" title={measure.caption}>
                      <span class="tree__icon" aria-hidden="true">Σ</span>
                      <span class="tree__label">{measure.caption || measure.name}</span>
                    </button>
                  </li>
                {/each}
              </ul>
            {/if}
          </li>
        {/each}
        {#if metadata.measures.length === 0}
          <li class="tree__empty">No measures</li>
        {/if}
      </ul>
    </section>

    <section class="panel">
      <header class="panel__header">Dimensions</header>
      <ul class="tree">
        {#each metadata.dimensions.filter((d) => d.name !== "Measures") as dim}
          {@const did = `d:${dim.uniqueName}`}
          <li class="tree__node">
            <button type="button" class="tree__row tree__row--dim" onclick={() => toggle(did)} title={dim.caption}>
              <span class="tree__twisty">{expanded[did] === false ? "▸" : "▾"}</span>
              <span class="tree__icon" aria-hidden="true">📁</span>
              <span class="tree__label">{dim.caption || dim.name}</span>
            </button>
            {#if expanded[did] !== false}
              <ul class="tree">
                {#each dim.hierarchies ?? [] as hier}
                  {#each hier.levels ?? [] as lvl}
                    <li class="tree__node">
                      <button type="button" class="tree__row tree__row--level" draggable="true" title={lvl.caption}>
                        <span class="tree__icon" aria-hidden="true">—</span>
                        <span class="tree__label">{lvl.caption || lvl.name}</span>
                      </button>
                    </li>
                  {/each}
                {/each}
              </ul>
            {/if}
          </li>
        {/each}
        {#if metadata.dimensions.length === 0}
          <li class="tree__empty">No dimensions</li>
        {/if}
      </ul>
    </section>
  {/if}
</div>

<style>
  .panels {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    margin-top: var(--space-4);
  }
  .panels__hint {
    color: var(--fg-subtle);
    font-size: var(--fs-sm);
    margin: var(--space-3) 0 0;
  }
  .panel {
    background: var(--bg);
    border-top: 1px solid var(--border);
    padding-top: var(--space-3);
  }
  .panel__header {
    font-size: var(--fs-xs);
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--fg-muted);
    margin-bottom: var(--space-2);
  }
  .tree {
    list-style: none;
    margin: 0;
    padding-left: 0;
  }
  .tree .tree {
    padding-left: var(--space-4);
  }
  .tree__empty {
    color: var(--fg-subtle);
    font-size: var(--fs-sm);
    padding: var(--space-2);
  }
  .tree__row {
    display: flex;
    width: 100%;
    align-items: center;
    gap: var(--space-2);
    padding: 2px var(--space-1);
    background: transparent;
    border: 0;
    color: var(--fg);
    cursor: pointer;
    font: inherit;
    text-align: left;
    border-radius: var(--radius-sm);
  }
  .tree__row:hover { background: var(--bg-subtle); }
  .tree__row--group { font-weight: 600; }
  .tree__row--measure {
    color: var(--accent);
    cursor: grab;
  }
  .tree__row--dim { color: var(--fg); }
  .tree__row--level {
    color: var(--fg-muted);
    cursor: grab;
  }
  .tree__twisty {
    display: inline-block;
    width: 12px;
    color: var(--fg-subtle);
    font-size: 10px;
  }
  .tree__icon {
    width: 16px;
    text-align: center;
    color: var(--fg-subtle);
  }
  .tree__label {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .tree__count {
    color: var(--fg-subtle);
    font-size: var(--fs-xs);
  }
</style>
