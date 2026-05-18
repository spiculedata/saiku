<script lang="ts">
  /*
   * Dashboards landing page. Lists every .saikudash file in the JCR
   * repository, surfaces "Open" / "New dashboard" / "Delete" actions,
   * and is the natural entry point from the topbar.
   *
   * The new-dashboard flow prompts for a name + relative path, creates a
   * fresh Dashboard via newDashboard(), saves it through DashboardResource,
   * and navigates to the editor. The save path is the same one the editor
   * uses (.saikudash extension required by the server).
   */

  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { base } from "$app/paths";
  import { listRepository, flatten, type RepositoryNode } from "$lib/api/repository";
  import {
    deleteDashboard,
    newDashboard,
    normaliseDashboardPath,
    saveDashboard,
  } from "$lib/api/dashboards";
  import { session } from "$lib/stores/session.svelte";

  let entries = $state<RepositoryNode[]>([]);
  let loading = $state<boolean>(true);
  let loadError = $state<string | null>(null);
  let creating = $state<boolean>(false);
  let createError = $state<string | null>(null);

  async function refresh(): Promise<void> {
    loading = true;
    loadError = null;
    try {
      const tree = await listRepository(["saikudash"]);
      const flat = flatten(tree);
      entries = flat.filter(
        (n) => n.type === "FILE" && (n.fileType === "saikudash" || n.path.endsWith(".saikudash")),
      );
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  onMount(() => {
    void refresh();
  });

  function defaultHomePath(): string {
    // Suggest /homes/<user> as the starting prefix so a brand-new user
    // doesn't pick the repo root (which the save endpoint trips on —
    // saiku#878 follow-up known issue).
    const u = session.current?.username;
    return u ? `homes/${u}` : "homes";
  }

  async function handleNew(): Promise<void> {
    creating = true;
    createError = null;
    try {
      // eslint-disable-next-line no-alert
      const rawName = window.prompt(
        "Dashboard name (e.g. \"Sales overview\")",
        "Untitled dashboard",
      );
      if (rawName == null) return; // cancelled
      const name = rawName.trim() || "Untitled dashboard";
      // eslint-disable-next-line no-alert
      const rawPath = window.prompt(
        "Repository path (e.g. " + defaultHomePath() + "/my-dashboard.saikudash)",
        defaultHomePath() + "/" + slugify(name) + ".saikudash",
      );
      if (rawPath == null) return;
      let path: string;
      try {
        path = normaliseDashboardPath(rawPath, session.current?.username ?? "");
      } catch (e: unknown) {
        createError = e instanceof Error ? e.message : String(e);
        return;
      }
      if (!path.endsWith(".saikudash")) {
        createError = "Path must end with .saikudash.";
        return;
      }
      const fresh = newDashboard(name);
      await saveDashboard(path, fresh);
      // Hand off to the editor at the new path.
      await goto(`${base}/dashboards/${path}`);
    } catch (e: unknown) {
      createError = e instanceof Error ? e.message : String(e);
    } finally {
      creating = false;
    }
  }

  async function handleDelete(path: string): Promise<void> {
    // eslint-disable-next-line no-alert
    if (!window.confirm(`Delete ${path}? This can't be undone.`)) return;
    try {
      await deleteDashboard(path);
      await refresh();
    } catch (e: unknown) {
      // eslint-disable-next-line no-alert
      window.alert(`Delete failed: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  function slugify(s: string): string {
    return s
      .toLowerCase()
      .trim()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 60) || "dashboard";
  }

  function basename(path: string): string {
    const p = path.split("/").pop() ?? path;
    return p.endsWith(".saikudash") ? p.slice(0, -".saikudash".length) : p;
  }
</script>

<div class="page">
  <header class="head">
    <h1>Dashboards</h1>
    <button type="button" class="btn primary" onclick={handleNew} disabled={creating}>
      {creating ? "Creating…" : "+ New dashboard"}
    </button>
  </header>

  {#if createError}
    <div class="error">{createError}</div>
  {/if}

  {#if loading}
    <p class="muted">Loading dashboards…</p>
  {:else if loadError}
    <div class="error">{loadError}</div>
  {:else if entries.length === 0}
    <p class="muted">
      No dashboards yet. Click <strong>+ New dashboard</strong> to create the first one.
    </p>
  {:else}
    <ul class="list">
      {#each entries as e (e.path)}
        <li class="row">
          <a class="link" href="{base}/dashboards/{e.path}" title={e.path}>
            <span class="name">{basename(e.path)}</span>
            <span class="path">{e.path}</span>
          </a>
          <button type="button" class="btn danger" onclick={() => handleDelete(e.path)} title="Delete">
            Delete
          </button>
        </li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  .page {
    display: flex;
    flex-direction: column;
    gap: 1rem;
    padding: 1.5rem;
    flex: 1;
    min-width: 0;
    height: 100%;
    box-sizing: border-box;
    overflow-y: auto;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
  }
  .head h1 {
    margin: 0;
    font-size: 1.25rem;
  }
  .btn {
    padding: 0.5rem 0.875rem;
    border: 1px solid var(--border-strong);
    background: var(--bg);
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.875rem;
  }
  .btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .btn.primary {
    background: var(--accent);
    color: white;
    border-color: var(--accent);
  }
  .btn.danger {
    color: var(--danger);
  }
  .btn.danger:hover {
    background: color-mix(in srgb, var(--danger) 12%, transparent);
  }
  .muted {
    color: var(--fg-muted);
  }
  .error {
    padding: 0.5rem 0.75rem;
    background: color-mix(in srgb, var(--danger) 14%, transparent);
    color: var(--danger);
    border-radius: 4px;
    font-size: 0.875rem;
  }
  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.375rem;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    padding: 0.5rem 0.75rem;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
  }
  .row:hover {
    background: var(--bg-subtle);
  }
  .link {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 0.125rem;
    text-decoration: none;
    color: inherit;
    min-width: 0;
  }
  .name {
    font-weight: 500;
  }
  .path {
    font-size: 0.75rem;
    color: var(--fg-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
</style>
