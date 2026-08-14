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
    /** saiku#1804: the tile's own cube, named in the filtered message. */
    cubeName?: string | null;
  }
  let { message = null, filtered = false, onReset, cubeName = null }: Props = $props();

  /* saiku#1804: tiles carry their own cube, so one page can mix them — and a
     scope valid for one cube can be empty in another (FoodMart ships to US
     stores only, so selecting Mexico empties every Warehouse tile while the
     Store tiles answer fine). A row of KPIs where two show numbers and two show
     "No data matches the current filters" reads as two broken tiles. Naming the
     cube turns it into a fact about the data. */
  const filteredMessage = $derived(
    cubeName
      ? i18n
          .t("tile.empty.filteredIn", "No {cube} data matches the current filters.")
          .replace("{cube}", cubeName)
      : i18n.t("tile.empty.filtered", "No data matches the current filters."),
  );
</script>

<div class="h-full w-full box-border flex flex-col items-center justify-center gap-2 p-3 text-center text-fg-muted">
  <Inbox size={22} aria-hidden="true" />
  <p class="msg">
    {message ?? (filtered ? filteredMessage : i18n.t("tile.empty", "No data."))}
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
    color: var(--saiku-app-fg, hsl(var(--fg)));
    background: var(--saiku-app-ground, hsl(var(--bg-subtle)));
    border: 1px solid var(--saiku-app-card-border, hsl(var(--border)));
    border-radius: 4px;
    cursor: pointer;
  }
  .reset:hover {
    background: var(--saiku-app-ground, hsl(var(--bg-muted)));
  }
  .reset:focus-visible {
    outline: 2px solid hsl(var(--primary));
    outline-offset: 1px;
  }
</style>
