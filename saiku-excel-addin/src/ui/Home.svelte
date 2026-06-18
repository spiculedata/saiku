<script lang="ts">
  import { onMount } from "svelte";
  import { listQueries, removeQuery } from "../lib/queryStore";
  import type { SavedQuery } from "../lib/queryStore";
  import { runQuery } from "../lib/executor";
  import { writeTable } from "../lib/sheetWriter";

  const { onNew, onEdit }: { onNew: () => void; onEdit: (q: SavedQuery) => void } = $props();

  let queries = $state<SavedQuery[]>([]);
  let loadError = $state<string | null>(null);
  let refreshing = $state<Record<string, string>>({});
  let refreshingAll = $state(false);

  onMount(async () => {
    await loadQueries();
  });

  async function loadQueries() {
    try {
      queries = await listQueries();
    } catch (e) {
      loadError = e instanceof Error ? e.message : "Failed to load queries";
    }
  }

  async function refresh(q: SavedQuery) {
    refreshing = { ...refreshing, [q.id]: "running" };
    try {
      const r = await runQuery(q.request);
      if (!r.ok) {
        refreshing = { ...refreshing, [q.id]: `Error: ${r.error}` };
        return;
      }
      await writeTable(q.sheet, r.grid);
      refreshing = { ...refreshing, [q.id]: "done" };
      setTimeout(() => {
        refreshing = Object.fromEntries(Object.entries(refreshing).filter(([k]) => k !== q.id));
      }, 2000);
    } catch (e) {
      refreshing = { ...refreshing, [q.id]: `Error: ${e instanceof Error ? e.message : String(e)}` };
    }
  }

  async function refreshAll() {
    refreshingAll = true;
    for (const q of queries) {
      await refresh(q);
    }
    refreshingAll = false;
  }

  async function handleRemove(q: SavedQuery) {
    try {
      await removeQuery(q.id);
      queries = queries.filter((x) => x.id !== q.id);
    } catch (e) {
      loadError = e instanceof Error ? e.message : "Failed to remove query";
    }
  }
</script>

<div class="home">
  <div class="header">
    <h2>Saiku Queries</h2>
    <div class="header-actions">
      <button class="btn-icon" onclick={refreshAll} disabled={refreshingAll || queries.length === 0} title="Refresh all">
        {#if refreshingAll}<span class="spinner">⟳</span>{:else}⟳ All{/if}
      </button>
      <button class="btn-primary" onclick={onNew}>＋ New query</button>
    </div>
  </div>

  {#if loadError}
    <div class="error" role="alert">{loadError}</div>
  {:else if queries.length === 0}
    <div class="empty">
      <p>No queries yet.</p>
      <p>Click <strong>＋ New query</strong> to get started.</p>
    </div>
  {:else}
    <ul class="query-list">
      {#each queries as q (q.id)}
        <li class="query-item">
          <div class="query-info">
            <span class="query-title">{q.title}</span>
            <span class="query-sheet">{q.sheet}</span>
          </div>
          <div class="query-actions">
            {#if refreshing[q.id]}
              <span class={refreshing[q.id].startsWith("Error") ? "status-error" : refreshing[q.id] === "done" ? "status-ok" : "status-busy"}>
                {refreshing[q.id] === "running" ? "⟳" : refreshing[q.id] === "done" ? "✓" : refreshing[q.id]}
              </span>
            {:else}
              <button class="btn-small" onclick={() => onEdit(q)}>Edit</button>
              <button class="btn-small btn-icon-refresh" onclick={() => refresh(q)} title="Refresh">↻</button>
              <button class="btn-small btn-danger" onclick={() => handleRemove(q)} title="Remove">✕</button>
            {/if}
          </div>
        </li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  .home {
    padding: 12px 16px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 8px;
  }

  h2 {
    font-size: 15px;
    font-weight: 700;
    margin: 0;
    color: #323130;
  }

  .header-actions {
    display: flex;
    gap: 6px;
    align-items: center;
  }

  .empty {
    text-align: center;
    color: #605e5c;
    font-size: 13px;
    padding: 24px 0;
  }

  .error {
    font-size: 13px;
    color: #a4262c;
    padding: 6px 8px;
    background: #fde7e9;
    border-radius: 2px;
  }

  .query-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .query-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 10px;
    background: #f3f2f1;
    border-radius: 4px;
    gap: 8px;
  }

  .query-info {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
    overflow: hidden;
  }

  .query-title {
    font-size: 13px;
    font-weight: 600;
    color: #323130;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .query-sheet {
    font-size: 11px;
    color: #605e5c;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .query-actions {
    display: flex;
    gap: 4px;
    align-items: center;
    flex-shrink: 0;
  }

  .btn-primary {
    font-family: Segoe UI, system-ui, sans-serif;
    font-size: 12px;
    font-weight: 600;
    color: #fff;
    background: #0078d4;
    border: none;
    border-radius: 2px;
    padding: 5px 10px;
    cursor: pointer;
    white-space: nowrap;
  }

  .btn-primary:hover:not(:disabled) { background: #106ebe; }
  .btn-primary:disabled { background: #c8c6c4; cursor: default; }

  .btn-icon {
    font-family: Segoe UI, system-ui, sans-serif;
    font-size: 12px;
    color: #323130;
    background: #fff;
    border: 1px solid #8a8886;
    border-radius: 2px;
    padding: 4px 8px;
    cursor: pointer;
    white-space: nowrap;
  }

  .btn-icon:hover:not(:disabled) { background: #f3f2f1; }
  .btn-icon:disabled { color: #a19f9d; cursor: default; }

  .btn-small {
    font-family: Segoe UI, system-ui, sans-serif;
    font-size: 11px;
    color: #323130;
    background: #fff;
    border: 1px solid #8a8886;
    border-radius: 2px;
    padding: 2px 7px;
    cursor: pointer;
  }

  .btn-small:hover { background: #f3f2f1; }

  .btn-icon-refresh { font-size: 13px; padding: 2px 6px; }

  .btn-danger { color: #a4262c; border-color: #a4262c; }
  .btn-danger:hover { background: #fde7e9; }

  .spinner { display: inline-block; animation: spin 0.8s linear infinite; }
  @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

  .status-busy { font-size: 13px; color: #0078d4; }
  .status-ok { font-size: 13px; color: #107c10; }
  .status-error { font-size: 11px; color: #a4262c; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
