<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import type { QueryResult } from "$lib/api/query";
  import { i18n } from "$lib/stores/i18n.svelte";

  interface Props {
    result: QueryResult | null;
    open: boolean;
    onClose: () => void;
  }

  let { result, open, onClose }: Props = $props();
</script>

<Modal title={i18n.t("modal.drillthroughResult.title")} {open} size="xl" onClose={onClose}>
  {#if !result}
    <p class="empty">{i18n.t("cubes.loading")}</p>
  {:else if result.error}
    <p class="callout callout--danger">{result.error}</p>
  {:else if !result.cellset || result.cellset.length === 0}
    <p class="empty">{i18n.t("modal.drillthroughResult.noRows")}</p>
  {:else}
    <div class="scroll">
      <table class="dt">
        <tbody>
          {#each result.cellset as row, r}
            <tr>
              {#each row as c}
                {#if r === 0}
                  <th>{c.value}</th>
                {:else if c.properties?.numeric === "true"}
                  <!--
                    Arrow drillthrough responses tag numeric columns so we can
                    right-align (matching the CellsetTable style). JSON
                    drillthrough carries no per-column type info, so those
                    cells fall through to the default left-aligned <td>.
                  -->
                  <td class="n">{c.value}</td>
                {:else}
                  <td>{c.value}</td>
                {/if}
              {/each}
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
    <p class="hint">{(result.cellset.length - 1).toLocaleString()} {i18n.t("units.rows")} · {result.runtime ?? 0} {i18n.t("units.ms")}</p>
  {/if}
  {#snippet footer()}
    <button type="button" class="btn btn--primary" onclick={onClose}>{i18n.t("modal.close")}</button>
  {/snippet}
</Modal>

<style>
  .scroll { max-height: 60vh; overflow: auto; border: 1px solid var(--border); border-radius: var(--radius-sm); }
  .dt { border-collapse: separate; border-spacing: 0; width: 100%; font-size: var(--fs-sm); }
  .dt th, .dt td { padding: 3px 9px; border-right: 1px solid var(--border); border-bottom: 1px solid var(--border); white-space: nowrap; text-align: left; }
  .dt td.n { text-align: right; font-variant-numeric: tabular-nums; }
  .dt th { position: sticky; top: 0; background: var(--bg-muted); color: var(--fg); font-weight: var(--weight-semibold); z-index: 1; }
  .empty { color: var(--fg-muted); padding: var(--space-3); }
  .hint { color: var(--fg-subtle); font-size: var(--fs-xs); margin-top: var(--space-2); }
</style>
