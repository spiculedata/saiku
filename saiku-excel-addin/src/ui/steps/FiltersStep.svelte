<script lang="ts">
  import type { DimPick, FilterPick } from "../../lib/types";
  import type { ServerCtx } from "../../lib/http";
  import { searchMembers } from "../../lib/discovery";

  const {
    ctx,
    cubeId,
    dimensions,
    filters,
    onUpdate,
  }: {
    ctx: ServerCtx;
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
      const res = await searchMembers(ctx, cubeId, d, q);
      results = { ...results, [key]: res };
    } catch (e) {
      let msg: string;
      if (e instanceof TypeError) {
        msg = "Can't reach this server, or it hasn't allowed the add-in (CORS)";
      } else {
        msg = e instanceof Error ? e.message : "Search failed";
      }
      searchErrors = { ...searchErrors, [key]: msg };
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

  // Extract the human-readable caption from an MDX unique name like [Dim].[Hier].[Level].&[Member]
  function memberCaption(results: { uniqueName: string; caption: string }[], uniqueName: string): string {
    const found = results.find((r) => r.uniqueName === uniqueName);
    if (found?.caption) return found.caption;
    // Fallback: strip MDX brackets from the last segment
    return uniqueName.split("].[").pop()?.replace(/[\[\]]/g, "") ?? uniqueName;
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
        {@const dimResults = results[key] ?? []}
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

          {#if dimResults.length}
            <ul class="results-list">
              {#each dimResults as r (r.uniqueName)}
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
                  <span class="chip-label" title={m}>{memberCaption(dimResults, m)}</span>
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
  .step { display: flex; flex-direction: column; gap: var(--space-2); flex: 1; min-height: 0; }

  .step-hint { font-size: var(--fs-sm); color: var(--fg-muted); margin: 0; }

  .empty { font-size: var(--fs-sm); color: var(--fg-muted); padding: var(--space-3) 0; }

  .filter-list {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    flex: 1;
    min-height: 0;
    overflow-y: auto;
  }

  .filter-group {
    background: var(--bg-muted);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    padding: var(--space-2) 10px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .filter-header {
    display: flex;
    align-items: center;
    gap: var(--space-1);
    flex-wrap: wrap;
  }

  .filter-dim { font-size: var(--fs-xs); font-weight: var(--fw-bold); color: var(--accent); }
  .filter-sep { font-size: 11px; color: var(--fg-subtle); }
  .filter-hier { font-size: var(--fs-xs); color: var(--fg); }
  .filter-level { font-size: var(--fs-xs); color: var(--fg-muted); }

  .search-row {
    display: flex;
    gap: var(--space-1);
  }

  input[type="search"] {
    font-family: var(--font);
    font-size: var(--fs-xs);
    flex: 1;
    padding: 4px 6px;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    outline: none;
    color: var(--fg);
    background: var(--bg);
  }

  input[type="search"]:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
  input[type="search"]:disabled { background: var(--bg-subtle); }

  .btn-search {
    font-family: var(--font);
    font-size: var(--fs-xs);
    padding: 4px 8px;
    border: 1px solid var(--accent);
    border-radius: var(--radius-sm);
    color: var(--accent);
    background: var(--bg);
    cursor: pointer;
    white-space: nowrap;
  }

  .btn-search:hover:not(:disabled) { background: var(--accent-soft); }
  .btn-search:disabled { color: var(--fg-subtle); border-color: var(--border-strong); cursor: default; }

  .error {
    font-size: 11px;
    color: var(--danger);
    padding: 4px 6px;
    background: var(--danger-soft);
    border-radius: var(--radius-sm);
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
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }

  .result-item {
    font-family: var(--font);
    font-size: var(--fs-xs);
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    text-align: left;
    padding: 4px 8px;
    border: none;
    background: transparent;
    cursor: pointer;
    color: var(--fg);
  }

  .result-item:hover:not(:disabled) { background: var(--bg-muted); }
  .result-item:disabled { color: var(--fg-subtle); cursor: default; }

  .result-caption { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .add-icon { color: var(--accent); font-size: var(--fs-md); flex-shrink: 0; }
  .added-icon { color: var(--success); font-size: var(--fs-xs); flex-shrink: 0; }

  .no-results { font-size: 11px; color: var(--fg-muted); padding: 4px 0; }

  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1);
  }

  .chip {
    display: flex;
    align-items: center;
    gap: 3px;
    background: var(--accent-soft);
    border: 1px solid var(--accent);
    border-radius: var(--radius-sm);
    padding: 2px 6px 2px 8px;
    font-size: 11px;
    color: var(--accent-strong);
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
    color: var(--accent);
    font-size: var(--fs-md);
    line-height: 1;
    padding: 0;
    flex-shrink: 0;
  }

  .chip-remove:hover { color: var(--danger); }
</style>
