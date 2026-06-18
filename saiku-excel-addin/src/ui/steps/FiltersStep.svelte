<script lang="ts">
  import type { DimPick, FilterPick } from "../../lib/types";
  import { searchMembers } from "../../lib/discovery";

  const {
    cubeId,
    dimensions,
    filters,
    onUpdate,
  }: {
    cubeId: string;
    dimensions: DimPick[];
    filters: FilterPick[];
    onUpdate: (filters: FilterPick[]) => void;
  } = $props();

  // Per-dimension search state
  let searches = $state<Record<string, string>>({});
  let results = $state<Record<string, { uniqueName: string; caption: string }[]>>({});
  let searching = $state<Record<string, boolean>>({});
  let searchErrors = $state<Record<string, string>>({});

  function getKey(d: DimPick) {
    return `${d.dimension}|${d.hierarchy}|${d.level}`;
  }

  function getFilter(d: DimPick): FilterPick | undefined {
    return filters.find(
      (f) => f.dimension === d.dimension && f.hierarchy === d.hierarchy && f.level === d.level
    );
  }

  function getMembers(d: DimPick): string[] {
    return getFilter(d)?.members ?? [];
  }

  async function doSearch(d: DimPick) {
    const key = getKey(d);
    const q = searches[key] ?? "";
    if (!q.trim()) {
      results = { ...results, [key]: [] };
      return;
    }
    searching = { ...searching, [key]: true };
    searchErrors = Object.fromEntries(Object.entries(searchErrors).filter(([k]) => k !== key));
    try {
      const res = await searchMembers(cubeId, d, q);
      results = { ...results, [key]: res };
    } catch (e) {
      searchErrors = { ...searchErrors, [key]: e instanceof Error ? e.message : "Search failed" };
    } finally {
      searching = { ...searching, [key]: false };
    }
  }

  function addMember(d: DimPick, uniqueName: string) {
    const existing = getFilter(d);
    if (existing?.members.includes(uniqueName)) return;
    const updated: FilterPick[] = filters.filter(
      (f) => !(f.dimension === d.dimension && f.hierarchy === d.hierarchy && f.level === d.level)
    );
    const members = [...(existing?.members ?? []), uniqueName];
    updated.push({ dimension: d.dimension, hierarchy: d.hierarchy, level: d.level, members });
    onUpdate(updated);
  }

  function removeMember(d: DimPick, uniqueName: string) {
    const existing = getFilter(d);
    if (!existing) return;
    const members = existing.members.filter((m) => m !== uniqueName);
    const updated: FilterPick[] = filters.filter(
      (f) => !(f.dimension === d.dimension && f.hierarchy === d.hierarchy && f.level === d.level)
    );
    if (members.length > 0) {
      updated.push({ dimension: d.dimension, hierarchy: d.hierarchy, level: d.level, members });
    }
    onUpdate(updated);
  }

  function handleKeydown(e: KeyboardEvent, d: DimPick) {
    if (e.key === "Enter") {
      doSearch(d);
    }
  }
</script>

<div class="step">
  <p class="step-hint">Optionally filter by dimension members. (Filters are optional.)</p>

  {#if dimensions.length === 0}
    <div class="empty">No dimensions selected. Go back and add dimensions first.</div>
  {:else}
    <div class="filter-list">
      {#each dimensions as d (getKey(d))}
        {@const key = getKey(d)}
        {@const members = getMembers(d)}
        <div class="filter-group">
          <div class="filter-header">
            <span class="filter-dim">{d.dimension}</span>
            <span class="filter-sep">›</span>
            <span class="filter-hier">{d.hierarchy}</span>
            <span class="filter-sep">›</span>
            <span class="filter-level">{d.level}</span>
          </div>

          <div class="search-row">
            <input
              type="search"
              placeholder="Search members…"
              value={searches[key] ?? ""}
              oninput={(e) => { searches = { ...searches, [key]: (e.target as HTMLInputElement).value }; }}
              onkeydown={(e) => handleKeydown(e, d)}
              disabled={searching[key]}
            />
            <button class="btn-search" onclick={() => doSearch(d)} disabled={searching[key]}>
              {searching[key] ? "…" : "Search"}
            </button>
          </div>

          {#if searchErrors[key]}
            <div class="error">{searchErrors[key]}</div>
          {/if}

          {#if results[key]?.length}
            <ul class="results-list">
              {#each results[key] as r (r.uniqueName)}
                <li>
                  <button
                    class="result-item"
                    disabled={members.includes(r.uniqueName)}
                    onclick={() => addMember(d, r.uniqueName)}
                  >
                    <span class="result-caption">{r.caption}</span>
                    {#if !members.includes(r.uniqueName)}
                      <span class="add-icon">＋</span>
                    {:else}
                      <span class="added-icon">✓</span>
                    {/if}
                  </button>
                </li>
              {/each}
            </ul>
          {:else if !searching[key] && (searches[key] ?? "").trim()}
            <div class="no-results">No results found.</div>
          {/if}

          {#if members.length > 0}
            <div class="chips">
              {#each members as m (m)}
                <span class="chip">
                  <span class="chip-label" title={m}>{m.split("].[").pop()?.replace(/[\[\]]/g, "") ?? m}</span>
                  <button class="chip-remove" onclick={() => removeMember(d, m)} aria-label="Remove {m}">×</button>
                </span>
              {/each}
            </div>
          {/if}
        </div>
      {/each}
    </div>
  {/if}
</div>

<style>
  .step { display: flex; flex-direction: column; gap: 8px; flex: 1; min-height: 0; }

  .step-hint { font-size: 13px; color: #605e5c; margin: 0; }

  .empty { font-size: 13px; color: #605e5c; padding: 12px 0; }

  .filter-list {
    display: flex;
    flex-direction: column;
    gap: 12px;
    flex: 1;
    min-height: 0;
    overflow-y: auto;
  }

  .filter-group {
    background: #f3f2f1;
    border-radius: 4px;
    padding: 8px 10px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .filter-header {
    display: flex;
    align-items: center;
    gap: 4px;
    flex-wrap: wrap;
  }

  .filter-dim { font-size: 12px; font-weight: 700; color: #0078d4; }
  .filter-sep { font-size: 11px; color: #a19f9d; }
  .filter-hier { font-size: 12px; color: #323130; }
  .filter-level { font-size: 12px; color: #605e5c; }

  .search-row {
    display: flex;
    gap: 4px;
  }

  input[type="search"] {
    font-family: Segoe UI, system-ui, sans-serif;
    font-size: 12px;
    flex: 1;
    padding: 4px 6px;
    border: 1px solid #8a8886;
    border-radius: 2px;
    outline: none;
  }

  input[type="search"]:focus { border-color: #0078d4; }
  input[type="search"]:disabled { background: #edebe9; }

  .btn-search {
    font-family: Segoe UI, system-ui, sans-serif;
    font-size: 12px;
    padding: 4px 8px;
    border: 1px solid #0078d4;
    border-radius: 2px;
    color: #0078d4;
    background: #fff;
    cursor: pointer;
    white-space: nowrap;
  }

  .btn-search:hover:not(:disabled) { background: #deecf9; }
  .btn-search:disabled { color: #a19f9d; border-color: #a19f9d; cursor: default; }

  .error {
    font-size: 11px;
    color: #a4262c;
    padding: 4px 6px;
    background: #fde7e9;
    border-radius: 2px;
  }

  .results-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 1px;
    max-height: 120px;
    overflow-y: auto;
    background: #fff;
    border: 1px solid #edebe9;
    border-radius: 2px;
  }

  .result-item {
    font-family: Segoe UI, system-ui, sans-serif;
    font-size: 12px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    text-align: left;
    padding: 4px 8px;
    border: none;
    background: transparent;
    cursor: pointer;
    color: #323130;
  }

  .result-item:hover:not(:disabled) { background: #f3f2f1; }
  .result-item:disabled { color: #a19f9d; cursor: default; }

  .result-caption { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .add-icon { color: #0078d4; font-size: 14px; flex-shrink: 0; }
  .added-icon { color: #107c10; font-size: 12px; flex-shrink: 0; }

  .no-results { font-size: 11px; color: #605e5c; padding: 4px 0; }

  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  .chip {
    display: flex;
    align-items: center;
    gap: 3px;
    background: #deecf9;
    border: 1px solid #0078d4;
    border-radius: 12px;
    padding: 2px 6px 2px 8px;
    font-size: 11px;
    color: #0078d4;
    max-width: 160px;
  }

  .chip-label {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex: 1;
  }

  .chip-remove {
    background: none;
    border: none;
    cursor: pointer;
    color: #0078d4;
    font-size: 14px;
    line-height: 1;
    padding: 0;
    flex-shrink: 0;
  }

  .chip-remove:hover { color: #a4262c; }
</style>
