<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import {
    deleteSavedQuery,
    flatten,
    getResourceAcl,
    listRepository,
    moveSavedQuery,
    readSavedQuery,
    setResourceAcl,
    writeSavedQuery,
    type AclEntry,
    type RepositoryNode,
    type SavedQueryFile,
  } from "$lib/api/repository";
  import { listAllRoles } from "$lib/api/admin";
  import { session } from "$lib/stores/session.svelte";
  import { repository } from "$lib/stores/repository.svelte";
  import PermissionsModal from "$lib/modals/PermissionsModal.svelte";
  import Skeleton from "$lib/components/Skeleton.svelte";
  import EmptyState from "$lib/components/EmptyState.svelte";
  import type { ThinQuery } from "$lib/api/query";
  import { toasts } from "$lib/stores/toasts.svelte";
  import {
    FolderOpen,
    Folder,
    Copy,
    Pencil,
    ShieldCheck,
    Trash2,
    Search,
    Inbox,
    ChevronRight,
    Home,
  } from "lucide-svelte";

  /*
   * Saved-queries browser with folder navigation.
   *
   * Folder-aware tree walk over the repository — users navigate by
   * clicking folders, breadcrumb back up, or search the whole repo
   * for a query by name. Matches the affordance of the Save modal
   * so users build a mental model of where their saved work lives.
   *
   * Each .saiku file row keeps the per-row action set (Open / Copy /
   * Rename / Permissions / Delete) the prior flat list had.
   */
  interface Props {
    open: boolean;
    onOpenQuery: (path: string, query: ThinQuery) => void;
    onClose: () => void;
  }

  let { open, onOpenQuery, onClose }: Props = $props();

  let error = $state<string | null>(null);
  let search = $state<string>("");
  let currentPath = $state<string>("");
  let confirming = $state<SavedQueryFile | null>(null);
  let renaming = $state<SavedQueryFile | null>(null);
  let renameValue = $state<string>("");
  let aclEditing = $state<SavedQueryFile | null>(null);
  let aclInitial = $state<AclEntry | null>(null);
  let aclRoles = $state<string[]>([]);
  let aclLoading = $state<boolean>(false);

  // Default to the user's home so they land somewhere useful.
  function defaultHome(): string {
    const u = session.current?.username;
    return u ? `homes/${u}` : "";
  }

  $effect(() => {
    if (open) {
      error = null;
      currentPath = defaultHome();
      void repository.refresh();
    }
  });

  function isSavedQueryFile(n: RepositoryNode): boolean {
    return n.type === "FILE" && (n.fileType === "saiku" || n.path.endsWith(".saiku"));
  }

  function listChildrenAt(tree: RepositoryNode[], path: string): RepositoryNode[] {
    if (!path) return tree;
    const segments = path.split("/").filter(Boolean);
    let cursor: RepositoryNode[] | undefined = tree;
    for (const seg of segments) {
      const next: RepositoryNode | undefined = cursor?.find(
        (n) => n.type === "FOLDER" && n.name === seg,
      );
      if (!next) return [];
      cursor = next.repoObjects ?? [];
    }
    return cursor ?? [];
  }

  const allFlat = $derived(flatten(repository.tree));
  const allSavedFiles = $derived<SavedQueryFile[]>(
    allFlat
      .filter(isSavedQueryFile)
      .map((n) => ({ path: n.path, name: n.name, type: "saiku" as const })),
  );

  // When search is active, show flat results across the entire repo.
  // Otherwise show subfolders + files at currentPath.
  const searchResults = $derived.by(() => {
    const q = search.trim().toLowerCase();
    if (!q) return null;
    return allSavedFiles
      .filter(
        (f) =>
          f.name.toLowerCase().includes(q) ||
          f.path.toLowerCase().includes(q),
      )
      .slice(0, 100);
  });

  const currentFolders = $derived<RepositoryNode[]>(
    searchResults
      ? []
      : listChildrenAt(repository.tree, currentPath)
          .filter((n) => n.type === "FOLDER")
          .sort((a, b) => a.name.localeCompare(b.name)),
  );

  const currentFiles = $derived<SavedQueryFile[]>(
    searchResults ??
      listChildrenAt(repository.tree, currentPath)
        .filter(isSavedQueryFile)
        .map((n) => ({ path: n.path, name: n.name, type: "saiku" as const }))
        .sort((a, b) => a.name.localeCompare(b.name)),
  );

  const breadcrumbs = $derived.by(() => {
    const out: { label: string; path: string }[] = [{ label: i18n.t("repo.root"), path: "" }];
    const segments = currentPath.split("/").filter(Boolean);
    let acc = "";
    for (const seg of segments) {
      acc = acc ? `${acc}/${seg}` : seg;
      out.push({ label: seg, path: acc });
    }
    return out;
  });

  async function doOpen(entry: SavedQueryFile) {
    try {
      const q = await readSavedQuery(entry.path);
      onOpenQuery(entry.path, q);
    } catch (err) {
      toasts.danger(i18n.t("toast.openFailed"), err instanceof Error ? err.message : String(err));
    }
  }

  async function doDuplicate(entry: SavedQueryFile) {
    try {
      const q = await readSavedQuery(entry.path);
      const idx = entry.path.lastIndexOf("/");
      const folder = idx > 0 ? entry.path.slice(0, idx) : "";
      const baseName = entry.name.replace(/\.saiku$/i, "");
      const newName = `${i18n.t("saved.copyPrefix")} ${baseName}.saiku`;
      const newPath = folder ? `${folder}/${newName}` : newName;
      await writeSavedQuery(newPath, q);
      toasts.success(i18n.t("toast.duplicated"), newPath);
      await repository.refresh();
    } catch (err) {
      toasts.danger(i18n.t("toast.duplicateFailed"), err instanceof Error ? err.message : String(err));
    }
  }

  function beginRename(entry: SavedQueryFile) {
    renaming = entry;
    renameValue = entry.name.replace(/\.saiku$/i, "");
  }

  async function commitRename() {
    const entry = renaming;
    if (!entry) return;
    const trimmed = renameValue.trim();
    if (!trimmed) {
      renaming = null;
      return;
    }
    const newName = trimmed.endsWith(".saiku") ? trimmed : `${trimmed}.saiku`;
    if (newName === entry.name) {
      renaming = null;
      return;
    }
    const idx = entry.path.lastIndexOf("/");
    const folder = idx > 0 ? entry.path.slice(0, idx) : "";
    const newPath = folder ? `${folder}/${newName}` : newName;
    try {
      await moveSavedQuery(entry.path, newPath);
      toasts.success(i18n.t("toast.renamed"), newPath);
      renaming = null;
      await repository.refresh();
    } catch (err) {
      toasts.danger(i18n.t("toast.renameFailed"), err instanceof Error ? err.message : String(err));
    }
  }

  async function openAcl(entry: SavedQueryFile) {
    aclLoading = true;
    try {
      const [acl, roles] = await Promise.all([
        getResourceAcl(entry.path),
        listAllRoles(),
      ]);
      aclInitial = acl;
      aclRoles = roles;
      aclEditing = entry;
    } catch (err) {
      toasts.danger(i18n.t("toast.aclLoadFailed"), err instanceof Error ? err.message : String(err));
    } finally {
      aclLoading = false;
    }
  }

  async function saveAcl(acl: AclEntry) {
    const entry = aclEditing;
    if (!entry) return;
    try {
      await setResourceAcl(entry.path, acl);
      toasts.success(i18n.t("toast.aclSaved"), entry.path);
      aclEditing = null;
      aclInitial = null;
    } catch (err) {
      toasts.danger(i18n.t("toast.aclSaveFailed"), err instanceof Error ? err.message : String(err));
    }
  }

  async function commitDelete() {
    const entry = confirming;
    if (!entry) return;
    try {
      await deleteSavedQuery(entry.path);
      toasts.success(i18n.t("toast.deleted"), entry.path);
      confirming = null;
      await repository.refresh();
    } catch (err) {
      toasts.danger(i18n.t("toast.deleteFailed"), err instanceof Error ? err.message : String(err));
    }
  }

  function onRowKey(e: KeyboardEvent, entry: SavedQueryFile) {
    if (e.key === "Enter") {
      e.preventDefault();
      void doOpen(entry);
    }
  }
</script>

<Modal title={i18n.t("modal.open.title")} {open} size="lg" onClose={onClose}>
  <nav class="saved__crumbs" aria-label={i18n.t("repo.breadcrumb")}>
    {#each breadcrumbs as crumb, i (crumb.path)}
      {#if i > 0}<ChevronRight size={12} class="saved__crumb-sep" />{/if}
      <button
        type="button"
        class="saved__crumb"
        class:is-current={i === breadcrumbs.length - 1}
        onclick={() => (currentPath = crumb.path)}
      >
        {#if i === 0}<Home size={12} />{/if}
        {crumb.label}
      </button>
    {/each}
  </nav>

  <div class="saved__search">
    <Search size={14} />
    <input
      class="saved__search-input"
      placeholder={i18n.t("modal.open.searchPlaceholder")}
      bind:value={search}
    />
  </div>

  {#if error}
    <p class="callout callout--danger">{error}</p>
  {/if}

  {#if repository.loading}
    <Skeleton rows={5} variant="list" />
  {:else if currentFolders.length === 0 && currentFiles.length === 0}
    {#if search}
      <p class="hint">{i18n.t("saved.noMatches")}</p>
    {:else if currentPath === defaultHome() && allSavedFiles.length === 0}
      <EmptyState
        icon={Inbox}
        title={i18n.t("modal.open.empty")}
        description={i18n.t("modal.open.emptyHint")}
        compact
      />
    {:else}
      <p class="hint">{i18n.t("repo.folderEmpty")}</p>
    {/if}
  {:else}
    <ul class="saved__list">
      {#each currentFolders as f (f.path)}
        <li class="saved__row saved__row--folder">
          <button
            type="button"
            class="saved__main"
            onclick={() => (currentPath = f.path.replace(/^\/+|\/+$/g, ""))}
          >
            <span class="saved__icon"><Folder size={14} /></span>
            <span class="saved__name">{f.name}</span>
          </button>
        </li>
      {/each}
      {#each currentFiles as entry (entry.path)}
        <li class="saved__row">
          {#if renaming?.path === entry.path}
            <div class="saved__rename">
              <input
                class="saved__rename-input"
                bind:value={renameValue}
                onkeydown={(e) => {
                  if (e.key === "Enter") void commitRename();
                  else if (e.key === "Escape") renaming = null;
                }}
                autofocus
              />
              <button type="button" class="btn btn--primary" onclick={() => commitRename()}>{i18n.t("modal.save")}</button>
              <button type="button" class="btn" onclick={() => (renaming = null)}>{i18n.t("modal.cancel")}</button>
            </div>
          {:else}
            <button
              type="button"
              class="saved__main"
              ondblclick={() => doOpen(entry)}
              onkeydown={(e) => onRowKey(e, entry)}
              title={i18n.t("saved.openHint")}
            >
              <span class="saved__icon"><FolderOpen size={14} /></span>
              <span class="saved__name">{entry.name}</span>
              {#if searchResults}
                <span class="saved__path">{entry.path}</span>
              {/if}
            </button>
            <div class="saved__actions">
              <button type="button" class="icon-btn" title={i18n.t("saved.open")} onclick={() => doOpen(entry)}>
                <FolderOpen size={16} />
              </button>
              <button type="button" class="icon-btn" title={i18n.t("saved.duplicate")} onclick={() => doDuplicate(entry)}>
                <Copy size={16} />
              </button>
              <button type="button" class="icon-btn" title={i18n.t("saved.rename")} onclick={() => beginRename(entry)}>
                <Pencil size={16} />
              </button>
              {#if session.isAdmin}
                <button
                  type="button"
                  class="icon-btn"
                  title={i18n.t("saved.permissions")}
                  disabled={aclLoading}
                  onclick={() => void openAcl(entry)}
                >
                  <ShieldCheck size={16} />
                </button>
              {/if}
              <button
                type="button"
                class="icon-btn icon-btn--danger"
                title={i18n.t("modal.delete")}
                onclick={() => (confirming = entry)}
              >
                <Trash2 size={16} />
              </button>
            </div>
          {/if}
        </li>
      {/each}
    </ul>
  {/if}

  {#if confirming}
    <div class="saved__confirm">
      <p>{i18n.t("saved.deletePrompt").replace("{name}", confirming.name)}</p>
      <div class="saved__confirm-actions">
        <button type="button" class="btn" onclick={() => (confirming = null)}>{i18n.t("modal.cancel")}</button>
        <button type="button" class="btn btn--danger" onclick={() => commitDelete()}>{i18n.t("modal.delete")}</button>
      </div>
    </div>
  {/if}

  {#snippet footer()}
    <button type="button" class="btn" onclick={onClose}>{i18n.t("modal.close")}</button>
  {/snippet}
</Modal>

{#if aclEditing && aclInitial}
  <PermissionsModal
    open={true}
    path={aclEditing.path}
    allRoles={aclRoles}
    initial={aclInitial}
    onSave={saveAcl}
    onCancel={() => {
      aclEditing = null;
      aclInitial = null;
    }}
  />
{/if}

<style>
  .hint { color: var(--fg-muted); font-size: var(--fs-sm); margin: var(--space-2) 0; }

  .saved__crumbs {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 2px;
    padding: var(--space-2) var(--space-3);
    margin-bottom: var(--space-2);
    background: var(--bg-subtle);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    font-size: var(--fs-sm);
  }
  .saved__crumb {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 2px 6px;
    background: transparent;
    border: 0;
    border-radius: 3px;
    color: var(--fg-muted);
    cursor: pointer;
    font: inherit;
  }
  .saved__crumb:hover { background: var(--bg-hover); color: var(--fg); }
  .saved__crumb.is-current { color: var(--fg); font-weight: var(--weight-medium); }
  .saved__crumb-sep { color: var(--fg-subtle); }

  .saved__search {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: 6px 10px;
    margin-bottom: var(--space-2);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg-subtle);
    color: var(--fg-muted);
  }
  .saved__search-input {
    flex: 1;
    background: transparent;
    border: 0;
    color: var(--fg);
    font: inherit;
    outline: none;
  }
  .saved__list {
    list-style: none;
    padding: 0;
    margin: 0;
    max-height: 50vh;
    overflow-y: auto;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
  .saved__row {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
  }
  .saved__row + .saved__row { border-top: 1px solid var(--border); }
  .saved__row:hover { background: var(--bg-hover); }
  .saved__main {
    flex: 1;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    text-align: left;
    background: transparent;
    border: 0;
    color: var(--fg);
    cursor: pointer;
    font: inherit;
    padding: 0;
    min-width: 0;
  }
  .saved__icon { color: var(--fg-muted); display: inline-flex; flex-shrink: 0; }
  .saved__row--folder .saved__icon { color: var(--accent); }
  .saved__name { font-weight: var(--weight-medium); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; min-width: 0; }
  .saved__path { color: var(--fg-subtle); font-size: var(--fs-xs); }
  .saved__actions { display: flex; gap: 2px; }
  /* .icon-btn / .icon-btn--danger come from app.css */
  .saved__rename {
    display: flex;
    flex: 1;
    gap: var(--space-2);
    align-items: center;
  }
  .saved__rename-input {
    flex: 1;
    padding: 4px 8px;
    background: var(--bg);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    font: inherit;
  }
  .saved__confirm {
    margin-top: var(--space-3);
    padding: var(--space-3);
    border: 1px solid var(--danger);
    border-radius: var(--radius-sm);
    background: color-mix(in srgb, var(--danger) 10%, var(--bg));
  }
  .saved__confirm-actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-2);
    margin-top: var(--space-2);
  }
</style>
