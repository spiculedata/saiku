<script lang="ts">
  import { onMount } from "svelte";
  import { listQueries, removeQuery, loadRegistry } from "../lib/queryStore";
  import type { SavedQuery } from "../lib/queryStore";
  import type { ServerCtx } from "../lib/http";
  import { runQuery } from "../lib/executor";
  import { writeTable } from "../lib/sheetWriter";
  import { loadCredential } from "../lib/credentialStore";
  import { activeServer } from "../lib/serverRegistry";

  const {
    ctx,
    onNew,
    onEdit,
  }: {
    ctx: ServerCtx;
    onNew: () => void;
    onEdit: (q: SavedQuery) => void;
  } = $props();

  let queries = $state<SavedQuery[]>([]);
  let loadError = $state<string | null>(null);
  let refreshing = $state<Record<string, string>>({});
  let refreshingAll = $state(false);

  // Map of serverId → server label (for grouping)
  let serverLabels = $state<Record<string, string>>({});

  onMount(async () => {
    await loadQueries();
  });

  async function loadQueries() {
    try {
      queries = await listQueries();
      // Build serverId → label map for grouping header display
      const reg = await loadRegistry();
      const labels: Record<string, string> = {};
      for (const s of reg.servers) {
        labels[s.id] = s.label;
      }
      serverLabels = labels;
    } catch (e) {
      loadError = e instanceof Error ? e.message : "Failed to load queries";
    }
  }

  /** Build the server ctx for a given query (may differ from active ctx if query is from another server). */
  async function ctxForQuery(q: SavedQuery): Promise<ServerCtx | null> {
    // If query is from the active server, use the provided ctx directly
    const reg = await loadRegistry();
    const server = reg.servers.find(s => s.id === q.serverId);
    if (!server) return null;
    const activeEntry = activeServer(reg);
    if (activeEntry && activeEntry.id === q.serverId) return ctx;
    // Different server — load its credential
    const credential = await loadCredential(q.serverId);
    return { baseUrl: server.baseUrl, credential };
  }

  async function refresh(q: SavedQuery) {
    refreshing = { ...refreshing, [q.id]: "running" };
    try {
      const qCtx = await ctxForQuery(q);
      if (!qCtx) {
        refreshing = { ...refreshing, [q.id]: `Error: Server not found — sign in to ${serverLabels[q.serverId] ?? q.serverId}` };
        return;
      }
      const r = await runQuery(qCtx, q.request);
      if (!r.ok) {
        let msg = r.error;
        if (r.status.startsWith("HTTP_401") || r.status === "HTTP_401") {
          msg = `Sign in to ${serverLabels[q.serverId] ?? q.serverId} to refresh this query`;
        }
        refreshing = { ...refreshing, [q.id]: `Error: ${msg}` };
        return;
      }
      await writeTable(q.sheet, r.grid);
      refreshing = { ...refreshing, [q.id]: "done" };
      setTimeout(() => {
        refreshing = Object.fromEntries(Object.entries(refreshing).filter(([k]) => k !== q.id));
      }, 2000);
    } catch (e) {
      let msg = e instanceof Error ? e.message : String(e);
      // Detect network / CORS failure (TypeError from fetch)
      if (e instanceof TypeError) {
        msg = "Can't reach this server, or it hasn't allowed the add-in (CORS)";
      }
      refreshing = { ...refreshing, [q.id]: `Error: ${msg}` };
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

  /** Group queries by serverId, preserving insertion order of first-seen serverIds. */
  function groupByServer(qs: SavedQuery[]): { serverId: string; label: string; queries: SavedQuery[] }[] {
    const order: string[] = [];
    const map: Record<string, SavedQuery[]> = {};
    for (const q of qs) {
      if (!map[q.serverId]) { map[q.serverId] = []; order.push(q.serverId); }
      map[q.serverId].push(q);
    }
    return order.map(id => ({ serverId: id, label: serverLabels[id] ?? id, queries: map[id] }));
  }
</script>

<div class="home">
  <div class="header">
    <h2>Queries</h2>
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
    {@const groups = groupByServer(queries)}
    {#each groups as group (group.serverId)}
      {#if groups.length > 1}
        <div class="server-group-label">{group.label}</div>
      {/if}
      <ul class="query-list">
        {#each group.queries as q (q.id)}
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
    {/each}
  {/if}
</div>

<style>
  .home {
    padding: var(--space-3) var(--space-4);
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  h2 {
    font-size: var(--fs-md);
    font-weight: var(--fw-bold);
    margin: 0;
    color: var(--fg);
  }

  .header-actions {
    display: flex;
    gap: 6px;
    align-items: center;
  }

  .empty {
    text-align: center;
    color: var(--fg-muted);
    font-size: var(--fs-sm);
    padding: var(--space-5) 0;
  }

  .error {
    font-size: var(--fs-sm);
    color: var(--danger);
    padding: 6px 8px;
    background: var(--danger-soft);
    border-radius: var(--radius-sm);
  }

  .server-group-label {
    font-size: var(--fs-xs);
    font-weight: var(--fw-semibold);
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.05em;
    padding: 2px 0;
    margin-top: var(--space-1);
  }

  .query-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }

  .query-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--space-2) 10px;
    background: var(--bg-muted);
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
    gap: var(--space-2);
  }

  .query-info {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
    overflow: hidden;
  }

  .query-title {
    font-size: var(--fs-sm);
    font-weight: var(--fw-semibold);
    color: var(--fg);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .query-sheet {
    font-size: var(--fs-xs);
    color: var(--fg-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .query-actions {
    display: flex;
    gap: var(--space-1);
    align-items: center;
    flex-shrink: 0;
  }

  .btn-primary {
    font-family: var(--font);
    font-size: var(--fs-xs);
    font-weight: var(--fw-semibold);
    color: var(--on-accent);
    background: var(--accent);
    border: none;
    border-radius: var(--radius-sm);
    padding: 5px 10px;
    cursor: pointer;
    white-space: nowrap;
    box-shadow: var(--shadow-sm);
  }

  .btn-primary:hover:not(:disabled) { background: var(--accent-hover); }
  .btn-primary:disabled { background: var(--border-strong); cursor: default; }

  .btn-icon {
    font-family: var(--font);
    font-size: var(--fs-xs);
    color: var(--fg);
    background: var(--bg);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    padding: 4px 8px;
    cursor: pointer;
    white-space: nowrap;
  }

  .btn-icon:hover:not(:disabled) { background: var(--bg-muted); }
  .btn-icon:disabled { color: var(--fg-subtle); cursor: default; }

  .btn-small {
    font-family: var(--font);
    font-size: var(--fs-xs);
    color: var(--fg);
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    padding: 2px 7px;
    cursor: pointer;
  }

  .btn-small:hover { background: var(--bg-muted); }

  .btn-icon-refresh { font-size: var(--fs-sm); padding: 2px 6px; }

  .btn-danger { color: var(--danger); border-color: var(--danger); }
  .btn-danger:hover { background: var(--danger-soft); }

  .spinner { display: inline-block; animation: spin 0.8s linear infinite; }
  @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }

  .status-busy { font-size: var(--fs-sm); color: var(--accent); }
  .status-ok { font-size: var(--fs-sm); color: var(--success); }
  .status-error { font-size: var(--fs-xs); color: var(--danger); max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
