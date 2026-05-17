<script lang="ts">
  /*
   * Filter widget tile. Three sub-types selected by tile.widget:
   *   - single-select : single dropdown, one member at a time
   *   - multi-select  : chip-style multi-pick
   *   - date-range    : two date inputs translated to a member list
   *                     (placeholder member shape — actual member format
   *                     depends on the cube's time hierarchy; refined in
   *                     task #12 when we wire the schema-aware lookup)
   *
   * Selection writes to activeFilters.setWidgetValue(tile.id, filter).
   * The member catalogue is fetched lazily on first focus — we don't
   * want every filter widget on the dashboard hammering the schema
   * endpoint on initial mount.
   */

  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import { schemaCache } from "$lib/stores/schemaCache.svelte";
  import type { DashboardTile, DashboardFilter } from "$lib/api/dashboards";

  interface Props {
    tile: DashboardTile;
    readOnly?: boolean;
  }

  let { tile, readOnly = false }: Props = $props();

  let target = $derived(tile.target);
  let widgetKind = $derived(tile.widget ?? "single-select");

  // Track selection locally; sync to activeFilters on commit.
  let selectedMembers = $state<string[]>([]);
  let dateFrom = $state<string>("");
  let dateTo = $state<string>("");

  // Member catalogue, populated on first focus.
  let members = $state<{ uniqueName: string; caption: string }[]>([]);
  let membersLoading = $state(false);
  let membersError = $state<string | null>(null);

  async function loadMembers(): Promise<void> {
    if (members.length > 0 || membersLoading || !tile.cube || !target) return;
    membersLoading = true;
    membersError = null;
    try {
      // Touch the schema cache to ensure the cube is resolved — keeps the
      // applicability check fast for downstream tiles. (Result unused here;
      // the cache primes for sibling tiles.)
      await schemaCache.get(tile.cube);
      // Member search lives on the AI Query API. q="" returns the first
      // `limit` members on the level.
      const params = new URLSearchParams({
        cubeId: cubeIdString(tile.cube),
        dimension: target.dimension,
        hierarchy: target.hierarchy,
        level: target.level,
        limit: "100",
      });
      const res = await fetch(`/rest/saiku/api/ai/members/search?${params.toString()}`, {
        credentials: "include",
        headers: { Accept: "application/json" },
      });
      if (!res.ok) throw new Error(`members -> ${res.status}`);
      const hits = (await res.json()) as { uniqueName: string; caption: string }[];
      members = hits;
    } catch (e: unknown) {
      membersError = e instanceof Error ? e.message : String(e);
    } finally {
      membersLoading = false;
    }
  }

  function cubeIdString(cube: { connectionName: string; catalog: string; schema: string; cubeName: string }): string {
    return `${cube.connectionName}/${cube.catalog}/${cube.schema}/${cube.cubeName}`;
  }

  function commitSelection(uniqueNames: string[]): void {
    if (!target) return;
    const filter: DashboardFilter = {
      dimension: target.dimension,
      hierarchy: target.hierarchy,
      level: target.level,
      members: uniqueNames,
    };
    activeFilters.setWidgetValue(tile.id, filter);
  }

  function handleSingleChange(e: Event): void {
    const v = (e.target as HTMLSelectElement).value;
    selectedMembers = v ? [v] : [];
    commitSelection(selectedMembers);
  }

  function toggleMember(uniqueName: string): void {
    if (selectedMembers.includes(uniqueName)) {
      selectedMembers = selectedMembers.filter((m) => m !== uniqueName);
    } else {
      selectedMembers = [...selectedMembers, uniqueName];
    }
    commitSelection(selectedMembers);
  }

  function handleDateChange(): void {
    if (!dateFrom && !dateTo) {
      commitSelection([]);
      return;
    }
    // Placeholder member shape — date ranges aren't a Mondrian-native
    // concept, they need to expand into the appropriate level members
    // (Year / Quarter / Month). Real expansion lands with task #12 when
    // the schema-aware lookup is wired. For now we push the raw bounds
    // so the active-filter chip surface is honest about what's set.
    commitSelection([dateFrom || "", dateTo || ""].filter(Boolean));
  }
</script>

{#if !target}
  <div class="placeholder">Filter widget has no target dim/hier/level configured.</div>
{:else}
  <div class="filter-widget" data-widget={widgetKind}>
    <header class="label">{target.level}</header>
    {#if widgetKind === "single-select"}
      <select
        class="select"
        disabled={readOnly}
        onfocus={loadMembers}
        onchange={handleSingleChange}
        aria-label="Filter by {target.level}"
      >
        <option value="">— any —</option>
        {#each members as m (m.uniqueName)}
          <option value={m.uniqueName} selected={selectedMembers.includes(m.uniqueName)}>{m.caption}</option>
        {/each}
      </select>
    {:else if widgetKind === "multi-select"}
      <div class="multi" onfocusin={loadMembers}>
        {#if members.length === 0 && !membersLoading}
          <button type="button" class="load-btn" onclick={loadMembers} disabled={readOnly}>Load members…</button>
        {:else}
          {#each members as m (m.uniqueName)}
            <label class="multi-item">
              <input
                type="checkbox"
                checked={selectedMembers.includes(m.uniqueName)}
                disabled={readOnly}
                onchange={() => toggleMember(m.uniqueName)}
              />
              <span>{m.caption}</span>
            </label>
          {/each}
        {/if}
      </div>
    {:else if widgetKind === "date-range"}
      <div class="date-range">
        <input
          type="date"
          bind:value={dateFrom}
          onchange={handleDateChange}
          disabled={readOnly}
          aria-label="From"
        />
        <span class="sep">→</span>
        <input
          type="date"
          bind:value={dateTo}
          onchange={handleDateChange}
          disabled={readOnly}
          aria-label="To"
        />
      </div>
    {/if}
    {#if membersLoading}
      <div class="hint">Loading members…</div>
    {:else if membersError}
      <div class="hint error">Member load failed: {membersError}</div>
    {/if}
  </div>
{/if}

<style>
  .filter-widget {
    display: flex;
    flex-direction: column;
    gap: 0.375rem;
    padding: 0.25rem 0.5rem;
    height: 100%;
  }
  .label {
    font-size: 0.75rem;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--fg-muted);
  }
  .select {
    padding: 0.25rem 0.375rem;
    border: 1px solid var(--border, #d1d5db);
    border-radius: 4px;
    background: var(--bg-input, #fff);
    font-size: 0.875rem;
  }
  .multi {
    display: flex;
    flex-direction: column;
    gap: 0.125rem;
    overflow: auto;
    flex: 1;
    min-height: 0;
  }
  .multi-item {
    display: flex;
    align-items: center;
    gap: 0.375rem;
    font-size: 0.8125rem;
    cursor: pointer;
  }
  .load-btn {
    align-self: flex-start;
    padding: 0.25rem 0.5rem;
    border: 1px solid var(--border, #d1d5db);
    background: transparent;
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.8125rem;
  }
  .date-range {
    display: flex;
    align-items: center;
    gap: 0.375rem;
  }
  .date-range input {
    flex: 1;
    padding: 0.25rem;
    border: 1px solid var(--border, #d1d5db);
    border-radius: 4px;
    font-size: 0.8125rem;
  }
  .sep {
    color: var(--fg-muted);
  }
  .hint {
    font-size: 0.75rem;
    color: var(--fg-muted);
  }
  .hint.error {
    color: #991b1b;
  }
  .placeholder {
    padding: 0.5rem;
    font-size: 0.8125rem;
    color: var(--fg-muted);
    font-style: italic;
  }
</style>
