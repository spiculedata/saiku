<script lang="ts">
  /*
   * Bulk-operations bar for multi-selected tiles (issue #915).
   *
   * Floats in when 2+ tiles are selected in edit mode and offers the
   * cross-tile actions: duplicate the selection, delete the selection, or
   * clear it. Each action loops the corresponding dashboardStore bulk
   * mutation (a single dirty bump), then resets the selection so the bar
   * dismisses itself. Hidden entirely in read-only / viewer mode.
   */

  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import { tileSelection } from "$lib/stores/tileSelection.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { Copy, Trash2, X } from "lucide-svelte";

  interface Props {
    readOnly?: boolean;
  }

  let { readOnly = false }: Props = $props();

  const count = $derived(tileSelection.count);
  const visible = $derived(!readOnly && count >= 2);

  function handleDuplicate(): void {
    if (readOnly) return;
    dashboardStore.duplicateTiles(tileSelection.toArray());
    tileSelection.clear();
  }

  function handleDelete(): void {
    if (readOnly) return;
    dashboardStore.removeTiles(tileSelection.toArray());
    tileSelection.clear();
  }

  function handleClear(): void {
    tileSelection.clear();
  }
</script>

{#if visible}
  <div class="bulk-bar" role="toolbar" aria-label={i18n.t("dashboard.bulk.label")}>
    <span class="text-sm font-medium text-fg-muted pr-1">{i18n.t("dashboard.bulk.selected").replace("{n}", String(count))}</span>
    <button type="button" class="bulk-bar__btn" onclick={handleDuplicate}>
      <Copy size={14} aria-hidden="true" />
      <span>{i18n.t("dashboard.bulk.duplicate")}</span>
    </button>
    <button type="button" class="bulk-bar__btn bulk-bar__btn--danger" onclick={handleDelete}>
      <Trash2 size={14} aria-hidden="true" />
      <span>{i18n.t("dashboard.bulk.delete")}</span>
    </button>
    <button
      type="button"
      class="bulk-bar__btn bulk-bar__btn--ghost"
      onclick={handleClear}
      aria-label={i18n.t("dashboard.bulk.clear")}
      title={i18n.t("dashboard.bulk.clear")}
    >
      <X size={14} aria-hidden="true" />
    </button>
  </div>
{/if}

<style>
.bulk-bar {
    display: inline-flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.375rem 0.5rem 0.375rem 0.75rem;
    border: 1px solid var(--border-strong);
    border-radius: 999px;
    background: var(--bg);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
    /* Float above the grid, centred near the bottom so it doesn't cover
       the toolbar or the tile a user is reaching for at the top. */
    position: fixed;
    left: 50%;
    bottom: 1.25rem;
    transform: translateX(-50%);
    z-index: 40;
  }
  .bulk-bar__btn {
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    padding: 0.3125rem 0.625rem;
    border: 1px solid var(--border-strong);
    background: var(--bg);
    color: var(--fg);
    border-radius: 999px;
    cursor: pointer;
    font-size: 0.8125rem;
  }
  .bulk-bar__btn:hover {
    background: var(--bg-subtle);
  }
  .bulk-bar__btn--danger {
    color: var(--danger);
    border-color: color-mix(in srgb, var(--danger) 50%, var(--border-strong));
  }
  .bulk-bar__btn--danger:hover {
    background: color-mix(in srgb, var(--danger) 12%, transparent);
  }
  .bulk-bar__btn--ghost {
    padding: 0.3125rem;
    border-color: transparent;
    color: var(--fg-muted);
  }
  .bulk-bar__btn--ghost:hover {
    color: var(--fg);
  }
</style>
