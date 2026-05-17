<script lang="ts">
  /*
   * Renders the active filter chips above the grid. Scaffold for task #12 —
   * the substance (chip-X clear, click-filter source tagging, hierarchy
   * precedence) lands when the activeFiltersStore and filter merge logic
   * arrive. For now the bar just surfaces dashboard.filters[] as static
   * chips so the layout is wired up.
   */

  import type { Dashboard } from "$lib/api/dashboards";

  interface Props {
    dashboard: Dashboard;
    readOnly?: boolean;
  }

  let { dashboard, readOnly = false }: Props = $props();

  let chips = $derived(
    dashboard.filters.map((f, i) => ({
      key: `${f.dimension}/${f.hierarchy}/${f.level}-${i}`,
      label: `${f.level}: ${f.members.length ? f.members.join(", ") : "(any)"}`,
    })),
  );
</script>

{#if chips.length > 0}
  <div class="filter-bar" role="region" aria-label="Active filters">
    {#each chips as chip (chip.key)}
      <span class="chip">
        {chip.label}
        {#if !readOnly}
          <button type="button" class="chip-x" aria-label="Remove filter" disabled>×</button>
        {/if}
      </span>
    {/each}
  </div>
{/if}

<style>
  .filter-bar {
    display: flex;
    flex-wrap: wrap;
    gap: 0.375rem;
    padding: 0.25rem 0;
  }
  .chip {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    padding: 0.25rem 0.5rem;
    background: var(--bg-chip, #f3f4f6);
    border-radius: 999px;
    font-size: 0.8125rem;
    color: var(--fg, inherit);
  }
  .chip-x {
    border: none;
    background: transparent;
    cursor: pointer;
    font-size: 1rem;
    line-height: 1;
    padding: 0 0.125rem;
    color: var(--fg-muted);
  }
  .chip-x:disabled { opacity: 0.4; cursor: not-allowed; }
</style>
