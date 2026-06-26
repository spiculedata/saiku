<script lang="ts">
  /*
   * Issue #933 — per-tile empty state.
   *
   * Shown when a tile's query succeeds but returns no rows. When the empty
   * result is caused by active dashboard filters, we say so and offer a
   * "Reset filters" button (clears click filters + restores panel defaults,
   * matching the toolbar's reset). With no active filters it's a plain
   * "no data" — nothing to reset.
   */
  import { Inbox, FilterX } from "lucide-svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  interface Props {
    message?: string | null;
    /** True when dashboard filters are narrowing this tile (enables reset). */
    filtered?: boolean;
    onReset?: () => void;
  }
  let { message = null, filtered = false, onReset }: Props = $props();
</script>

<div class="h-full w-full box-border flex flex-col items-center justify-center gap-2 p-3 text-center text-fg-muted">
  <Inbox size={22} aria-hidden="true" />
  <p class="msg">
    {message ??
      (filtered
        ? i18n.t("tile.empty.filtered", "No data matches the current filters.")
        : i18n.t("tile.empty", "No data."))}
  </p>
  {#if filtered && onReset}
    <button type="button" class="reset" onclick={onReset}>
      <FilterX size={14} aria-hidden="true" />
      {i18n.t("tile.resetFilters", "Reset filters")}
    </button>
  {/if}
</div>

<style>
.msg {
    margin: 0;
    font-size: 0.8125rem;
    max-width: 100%;
    overflow-wrap: anywhere;
  }
  .reset {
    display: inline-flex;
    align-items: center;
    gap: 0.35rem;
    padding: 0.25rem 0.6rem;
    font-size: 0.8125rem;
    color: var(--fg);
    background: var(--bg-subtle);
    border: 1px solid var(--border);
    border-radius: 4px;
    cursor: pointer;
  }
  .reset:hover {
    background: var(--bg-muted);
  }
  .reset:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 1px;
  }
</style>
