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
  import {
    flatten,
    getResourceAcl,
    listRepository,
    setResourceAcl,
    type AclEntry,
    type RepositoryNode,
  } from "$lib/api/repository";
  import { listAllRoles } from "$lib/api/admin";
  import {
    deleteDashboard,
    duplicateDashboard,
    loadDashboard,
    newDashboard,
    normaliseDashboardPath,
    toRepoRelative,
    saveDashboard,
  } from "$lib/api/dashboards";
  import { session } from "$lib/stores/session.svelte";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import PermissionsModal from "$lib/modals/PermissionsModal.svelte";
  import NewDashboardModal from "$lib/modals/NewDashboardModal.svelte";
  import ConfirmModal from "$lib/modals/ConfirmModal.svelte";
  import Skeleton from "$lib/components/Skeleton.svelte";
  import EmptyState from "$lib/components/EmptyState.svelte";
  import { Copy, ShieldCheck, LayoutDashboard } from "lucide-svelte";

  let entries = $state<RepositoryNode[]>([]);
  let loading = $state<boolean>(true);
  let loadError = $state<string | null>(null);
  let creating = $state<boolean>(false);
  let createError = $state<string | null>(null);
  let aclEditingPath = $state<string | null>(null);
  let aclInitial = $state<AclEntry | null>(null);
  let aclRoles = $state<string[]>([]);
  let aclLoading = $state<boolean>(false);
  /** Path of the dashboard currently being duplicated — used to disable
   *  the per-row Duplicate button while the load + save round-trip is
   *  in flight, so a double-click doesn't fire two duplicates. */
  let duplicatingPath = $state<string | null>(null);

  let newModalOpen = $state<boolean>(false);
  let deletingPath = $state<string | null>(null);

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

  function handleNew(): void {
    createError = null;
    newModalOpen = true;
  }

  async function onNewModalCreate(path: string, name: string): Promise<void> {
    newModalOpen = false;
    creating = true;
    createError = null;
    try {
      const finalPath = normaliseDashboardPath(path, session.current?.username ?? "");
      if (!finalPath.endsWith(".saikudash")) {
        createError = "Path must end with .saikudash.";
        return;
      }
      const fresh = newDashboard(name);
      await saveDashboard(finalPath, fresh);
      await goto(`${base}/dashboards/${finalPath}`);
    } catch (e: unknown) {
      createError = e instanceof Error ? e.message : String(e);
    } finally {
      creating = false;
    }
  }

  function handleDelete(path: string): void {
    deletingPath = path;
  }

  async function confirmDelete(): Promise<void> {
    const path = deletingPath;
    if (!path) return;
    deletingPath = null;
    try {
      await deleteDashboard(path);
      await refresh();
      toasts.success(i18n.t("toast.deleted"), path);
    } catch (e: unknown) {
      toasts.danger(
        i18n.t("toast.deleteFailed"),
        e instanceof Error ? e.message : String(e),
      );
    }
  }

  /** Duplicate the dashboard at {@code srcPath}: load it so we can
   *  default the new-name prompt to the real {@code Dashboard.name}
   *  (not the filename slug), prompt for a name + path, then call
   *  duplicateDashboard which clones with fresh ids and saves. Issue #939. */
  async function handleDuplicate(srcPath: string): Promise<void> {
    duplicatingPath = srcPath;
    createError = null;
    try {
      const source = await loadDashboard(srcPath);
      // eslint-disable-next-line no-alert
      const rawName = window.prompt("Copy name", `${source.name} (copy)`);
      if (rawName == null) return; // cancelled
      const name = rawName.trim() || `${source.name} (copy)`;
      // eslint-disable-next-line no-alert
      const rawPath = window.prompt(
        "Repository path for the copy",
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
      await duplicateDashboard(srcPath, path, name);
      await goto(`${base}/dashboards/${path}`);
    } catch (e: unknown) {
      createError = e instanceof Error ? e.message : String(e);
    } finally {
      duplicatingPath = null;
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

  async function openAcl(path: string): Promise<void> {
    aclLoading = true;
    try {
      const [acl, roles] = await Promise.all([getResourceAcl(path), listAllRoles()]);
      aclInitial = acl;
      aclRoles = roles;
      aclEditingPath = path;
    } catch (e: unknown) {
      toasts.danger(i18n.t("toast.aclLoadFailed"), e instanceof Error ? e.message : String(e));
    } finally {
      aclLoading = false;
    }
  }

  async function saveAcl(acl: AclEntry): Promise<void> {
    const path = aclEditingPath;
    if (!path) return;
    try {
      await setResourceAcl(path, acl);
      toasts.success(i18n.t("toast.aclSaved"), path);
      aclEditingPath = null;
      aclInitial = null;
    } catch (e: unknown) {
      toasts.danger(i18n.t("toast.aclSaveFailed"), e instanceof Error ? e.message : String(e));
    }
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
    <Skeleton rows={4} variant="list" />
  {:else if loadError}
    <div class="error">{loadError}</div>
  {:else if entries.length === 0}
    <EmptyState
      icon={LayoutDashboard}
      title="No dashboards yet"
      description="Build your first dashboard from cubes, queries, and KPIs."
      action={{ label: "+ New dashboard", onClick: handleNew }}
    />
  {:else}
    <ul class="list">
      {#each entries as e (e.path)}
        {@const relPath = toRepoRelative(e.path)}
        <li class="row">
          <a class="link" href="{base}/dashboards/{relPath}" title={relPath}>
            <span class="name">{basename(relPath)}</span>
            <span class="path">{relPath}</span>
          </a>
          {#if session.isAdmin}
            <button
              type="button"
              class="btn"
              disabled={aclLoading}
              onclick={() => void openAcl(relPath)}
              title={i18n.t("saved.permissions")}
              aria-label={i18n.t("saved.permissions")}
            >
              <ShieldCheck size={14} />
            </button>
          {/if}
          <button type="button" class="btn danger" onclick={() => handleDelete(relPath)} title="Delete">
            Delete
          </button>
        </li>
      {/each}
    </ul>
  {/if}
</div>

{#if aclEditingPath && aclInitial}
  <PermissionsModal
    open={true}
    path={aclEditingPath}
    allRoles={aclRoles}
    initial={aclInitial}
    onSave={saveAcl}
    onCancel={() => {
      aclEditingPath = null;
      aclInitial = null;
    }}
  />
{/if}

<NewDashboardModal
  defaultName="Untitled dashboard"
  defaultFolder={defaultHomePath()}
  open={newModalOpen}
  onCreate={onNewModalCreate}
  onCancel={() => (newModalOpen = false)}
/>

<ConfirmModal
  title="Delete dashboard"
  message={deletingPath ? `Delete "${deletingPath}"? This cannot be undone.` : ""}
  confirmLabel="Delete"
  variant="danger"
  open={deletingPath !== null}
  onConfirm={confirmDelete}
  onCancel={() => (deletingPath = null)}
/>

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
    font-weight: var(--weight-medium);
  }
  .path {
    font-size: 0.75rem;
    color: var(--fg-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
</style>
