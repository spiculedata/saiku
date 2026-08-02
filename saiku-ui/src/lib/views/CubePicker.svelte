<script lang="ts">
  import { datasources } from "$lib/stores/datasources.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { session } from "$lib/stores/session.svelte";
  import type { SaikuCube } from "$lib/api/discover";
  import { cubeKey } from "$lib/stores/datasources.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { RotateCw } from "lucide-svelte";

  interface Props {
    username: string;
  }

  let { username }: Props = $props();

  $effect(() => {
    if (username && !datasources.loaded && !datasources.loading && !datasources.error) {
      datasources.load(username).then(() => {
        // saiku-cloud#631: kick off a background poll for late-arriving
        // cubes (cache-first engine model means warm-up cubes may
        // surface a few seconds after the initial /discover returns).
        // Polls /discover/refresh every 3s for up to 60s, stops once
        // the cube list is stable across 2 consecutive polls.
        // No-op on non-cloud deploys (the engine just returns the same
        // list on every poll, which triggers the stable-tick exit).
        if (datasources.loaded) {
          datasources.startPolling(username);
        }
      });
    }
  });

  /**
   * Encode an OSSIE-typed connection as a picker option value so we can round-trip through
   * the same <select> the OLAP path uses. The `ossie:` prefix distinguishes from OLAP cube
   * keys (which don't start with a scheme).
   */
  const OSSIE_PREFIX = "ossie:";

  let selectedKey = $derived(
    selection.mode === "ossie" && selection.ossie
      ? `${OSSIE_PREFIX}${selection.ossie.connectionName}`
      : selection.cube
        ? cubeKey(selection.cube)
        : "",
  );

  // Keep the Ossie-connection list handy for the dropdown; they have empty catalogs so the
  // OLAP nested-loop below skips them entirely.
  let ossieConnections = $derived(datasources.connections.filter((c) => c.type === "OSSIE"));

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
    if (key.startsWith(OSSIE_PREFIX)) {
      const connectionName = key.substring(OSSIE_PREFIX.length);
      // Ossie datasources are one-model-per-connection for the MVP shape; the model name
      // (used to pick a semantic_model[] entry inside the YAML) defaults to the connection
      // name. F3's OssieSchemaTree can override this later if multi-model YAML support
      // lands.
      selection.selectOssie({ connectionName, modelName: connectionName });
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
  <div class="flex gap-2">
    <select
      id="cubes-select"
      class="flex-1 py-2 px-3 bg-bg text-fg border border-border-strong rounded-sm text-sm"
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
      {#if ossieConnections.length > 0}
        <optgroup label="Ossie semantic models">
          {#each ossieConnections as conn}
            <option value="{OSSIE_PREFIX}{conn.name}">{conn.name}</option>
          {/each}
        </optgroup>
      {/if}
    </select>
    <button
      type="button"
      class="icon-btn cube-picker__refresh"
      onclick={onRefresh}
      title={i18n.t("cubes.refresh")}
      aria-label={i18n.t("cubes.refresh")}
      disabled={datasources.loading}
    >
      <RotateCw size={14} class={datasources.loading ? "spin" : ""} />
    </button>
  </div>
  {#if datasources.error}
    <p class="callout callout--danger">{datasources.error}</p>
  {/if}
  {#if !datasources.loading && datasources.connections.length === 0 && !datasources.error}
    <p class="m-0 text-sm text-fg-subtle">{i18n.t("cubes.empty")}</p>
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
    color: hsl(var(--fg-muted));
  }
  .cube-picker__refresh {
    /* match the select's vertical footprint so they line up at the same height */
    width: 36px;
    height: 36px;
  }
  .cube-picker__refresh :global(.spin) {
    animation: cube-picker-spin 900ms linear infinite;
  }
  @keyframes cube-picker-spin {
    to { transform: rotate(360deg); }
  }
</style>
