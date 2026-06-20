<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import { datasources } from "$lib/stores/datasources.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

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

<Modal title={i18n.t("modal.datasources.title")} {open} size="lg" {onClose}>
  <div class="flex justify-end mb-2">
    <Button variant="outline" onclick={refresh} disabled={datasources.loading}>⟳ {i18n.t("admin.refresh")}</Button>
  </div>
  {#if datasources.connections.length === 0}
    <p class="text-fg-muted">{i18n.t("modal.datasources.empty")}</p>
  {:else}
    <table class="grid">
      <thead>
        <tr>
          <th>{i18n.t("modal.datasources.col.connection")}</th><th>{i18n.t("modal.datasources.col.catalog")}</th><th>{i18n.t("modal.datasources.col.schema")}</th><th>{i18n.t("modal.datasources.col.cube")}</th>
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
                  <td>{sch.name || i18n.t("modal.datasources.defaultSchema")}</td>
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
    <Button onclick={onClose}>{i18n.t("modal.close")}</Button>
  {/snippet}
</Modal>

<style>
.grid { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
  .grid th, .grid td { border: 1px solid var(--border); padding: var(--space-1) var(--space-2); text-align: left; }
  .grid th { background: var(--bg-muted); }
</style>
