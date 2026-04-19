<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import type { QueryResult } from "$lib/api/query";

  interface Props {
    result: QueryResult | null;
    open: boolean;
    onClose: () => void;
  }

  let { result, open, onClose }: Props = $props();
</script>

<Modal title="Drillthrough result" {open} size="xl" onClose={onClose}>
  {#if !result}
    <p class="empty">Loading…</p>
  {:else if result.error}
    <p class="callout callout--danger">{result.error}</p>
  {:else if !result.cellset || result.cellset.length === 0}
    <p class="empty">No rows returned.</p>
  {:else}
    <div class="scroll">
      <table class="dt">
        <tbody>
          {#each result.cellset as row, r}
            <tr>
              {#each row as c}
                {#if r === 0}
                  <th>{c.value}</th>
                {:else}
                  <td>{c.value}</td>
                {/if}
              {/each}
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
    <p class="hint">{(result.cellset.length - 1).toLocaleString()} rows · {result.runtime ?? 0} ms</p>
  {/if}
  {#snippet footer()}
    <button type="button" class="btn btn--primary" onclick={onClose}>Close</button>
  {/snippet}
</Modal>

<style>
  .scroll { max-height: 60vh; overflow: auto; border: 1px solid var(--border); border-radius: var(--radius-sm); }
  .dt { border-collapse: separate; border-spacing: 0; width: 100%; font-size: var(--fs-sm); }
  .dt th, .dt td { padding: 3px 9px; border-right: 1px solid var(--border); border-bottom: 1px solid var(--border); white-space: nowrap; text-align: left; }
  .dt th { position: sticky; top: 0; background: var(--bg-muted); color: var(--fg); font-weight: 600; z-index: 1; }
  .empty { color: var(--fg-muted); padding: var(--space-3); }
  .hint { color: var(--fg-subtle); font-size: var(--fs-xs); margin-top: var(--space-2); }
</style>
