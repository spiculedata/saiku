<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { datasources } from "$lib/stores/datasources.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/DataSourcesModal.js. */
  interface Props {
    open: boolean;
    onRefresh: () => Promise<void>;
    onClose: () => void;
  }

  let { open, onRefresh, onClose }: Props = $props();

  async function refresh() {
    await onRefresh();
  }
</script>

<Modal title="Datasources" {open} size="lg" {onClose}>
  <div class="bar">
    <button type="button" class="btn" onclick={refresh} disabled={datasources.loading}>
      ⟳ Refresh
    </button>
  </div>
  {#if datasources.connections.length === 0}
    <p class="hint">No connections available.</p>
  {:else}
    <table class="grid">
      <thead>
        <tr>
          <th>Connection</th><th>Catalog</th><th>Schema</th><th>Cube</th>
        </tr>
      </thead>
      <tbody>
        {#each datasources.connections as c}
          {#each c.catalogs as cat}
            {#each cat.schemas as sch}
              {#each sch.cubes as cube}
                <tr>
                  <td>{c.name}</td>
                  <td>{cat.name}</td>
                  <td>{sch.name || "(default)"}</td>
                  <td>{cube.caption || cube.name}</td>
                </tr>
              {/each}
            {/each}
          {/each}
        {/each}
      </tbody>
    </table>
  {/if}
  {#snippet footer()}
    <button type="button" class="btn btn--primary" onclick={onClose}>Close</button>
  {/snippet}
</Modal>

<style>
  .bar { display: flex; justify-content: flex-end; margin-bottom: var(--space-2); }
  .grid { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
  .grid th, .grid td { border: 1px solid var(--border); padding: var(--space-1) var(--space-2); text-align: left; }
  .grid th { background: var(--bg-muted); }
  .hint { color: var(--fg-muted); }
</style>
