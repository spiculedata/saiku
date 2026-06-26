<script lang="ts">
  /*
   * Catalogue search / sort / view-toggle / tag+owner chip filters —
   * extracted from DashboardIndex (saiku#1234). Owns no business logic
   * of its own; everything funnels back to the parent via $bindable()
   * props on the filter state + callbacks for the few one-shot actions
   * (open new-folder modal, clear all filters).
   *
   * Display of "Clear filters" is parent-driven via `showClearFilters`
   * (true when any of search / tags / owners are non-empty) so the
   * delegate doesn't need to know about emptiness rules — keeps the
   * applicability rule in one place.
   */
  import { Folder, FolderPlus } from "lucide-svelte";
  import { Button } from "$lib/components/ui";
  import { i18n } from "$lib/stores/i18n.svelte";
  import type { SortKey } from "$lib/dashboard/catalogueFilter";

  interface Props {
    searchQuery: string;
    sortKey: SortKey;
    viewMode: "list" | "tree";
    showClearFilters: boolean;
    onClearFilters: () => void;
    onNewFolder: () => void;
  }

  let {
    searchQuery = $bindable(),
    sortKey = $bindable(),
    viewMode = $bindable(),
    showClearFilters,
    onClearFilters,
    onNewFolder,
  }: Props = $props();
</script>

<section class="catalogue-filters" aria-label="Catalogue filters">
  <input
    type="search"
    class="search"
    placeholder="Search dashboards by name or path…"
    bind:value={searchQuery}
    aria-label="Search dashboards"
  />
  <label class="sort">
    <span>Sort:</span>
    <select bind:value={sortKey} aria-label="Sort dashboards">
      <option value="name">Name</option>
      <option value="modified-desc">Last modified ↓</option>
      <option value="modified-asc">Last modified ↑</option>
    </select>
  </label>
  <div class="inline-flex gap-0" role="group" aria-label={i18n.t("dashboard.view.label")}>
    <Button variant="outline" class="view-btn {viewMode === "list" ? 'view-btn--on' : ''}" aria-pressed={viewMode === "list"} onclick={() => (viewMode = "list")}>
      {i18n.t("dashboard.view.list")}
    </Button>
    <Button variant="outline" class="view-btn {viewMode === "tree" ? 'view-btn--on' : ''}" aria-pressed={viewMode === "tree"} onclick={() => (viewMode = "tree")}>
      <Folder size={14} aria-hidden="true" />
      {i18n.t("dashboard.view.folders")}
    </Button>
  </div>
  {#if viewMode === "tree"}
    <Button variant="outline" onclick={onNewFolder}>
      <FolderPlus size={14} aria-hidden="true" />
      {i18n.t("dashboard.folder.new")}
    </Button>
  {/if}
  {#if showClearFilters}
    <Button variant="outline" onclick={onClearFilters}>
      Clear filters
    </Button>
  {/if}
</section>

<style>
.catalogue-filters {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    flex-wrap: wrap;
  }
  .catalogue-filters .search {
    flex: 1;
    min-width: 12rem;
    padding: 0.5rem 0.75rem;
    border: 1px solid var(--border-strong);
    border-radius: 4px;
    background: var(--bg);
    font-size: 0.875rem;
  }
  .catalogue-filters .sort {
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    font-size: 0.8125rem;
    color: var(--fg-muted);
  }
  .catalogue-filters select {
    padding: 0.375rem 0.5rem;
    border: 1px solid var(--border-strong);
    border-radius: 4px;
    background: var(--bg);
    font-size: 0.8125rem;
  }
</style>
