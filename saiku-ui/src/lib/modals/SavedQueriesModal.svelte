<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import {
    deleteSavedQuery,
    getResourceAcl,
    listSavedQueries,
    moveSavedQuery,
    readSavedQuery,
    setResourceAcl,
    writeSavedQuery,
    type AclEntry,
    type SavedQueryFile,
  } from "$lib/api/repository";
  import { listAllRoles } from "$lib/api/admin";
  import { session } from "$lib/stores/session.svelte";
  import PermissionsModal from "$lib/modals/PermissionsModal.svelte";
  import type { ThinQuery } from "$lib/api/query";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { FolderOpen, Copy, Pencil, ShieldCheck, Trash2, Search } from "lucide-svelte";

  interface Props {
    open: boolean;
    onOpenQuery: (path: string, query: ThinQuery) => void;
    onClose: () => void;
  }

  let { open, onOpenQuery, onClose }: Props = $props();

  let entries = $state<SavedQueryFile[]>([]);
  let loading = $state<boolean>(false);
  let error = $state<string | null>(null);
  let search = $state<string>("");
  let confirming = $state<SavedQueryFile | null>(null);
  let renaming = $state<SavedQueryFile | null>(null);
  let renameValue = $state<string>("");
  let aclEditing = $state<SavedQueryFile | null>(null);
  let aclInitial = $state<AclEntry | null>(null);
  let aclRoles = $state<string[]>([]);
  let aclLoading = $state<boolean>(false);

  async function refresh() {
    loading = true;
    error = null;
    try {
      entries = await listSavedQueries();
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    if (open) void refresh();
  });

  const filtered = $derived(
    search
      ? entries.filter(
          (e) =>
            e.name.toLowerCase().includes(search.toLowerCase()) ||
            e.path.toLowerCase().includes(search.toLowerCase()),
        )
      : entries,
  );

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
      await refresh();
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
      await refresh();
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
      await refresh();
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
  {#if loading}
    <p class="hint">{i18n.t("modal.open.loading")}</p>
  {:else if filtered.length === 0}
    <p class="hint">{search ? i18n.t("saved.noMatches") : i18n.t("modal.open.empty")}</p>
  {:else}
    <ul class="saved__list">
      {#each filtered as entry (entry.path)}
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
              <span class="saved__name">{entry.name}</span>
              <span class="saved__path">{entry.path}</span>
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
  .saved__row:hover { background: var(--bg-subtle); }
  .saved__main {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 2px;
    text-align: left;
    background: transparent;
    border: 0;
    color: var(--fg);
    cursor: pointer;
    font: inherit;
    padding: 0;
  }
  .saved__name { font-weight: 500; }
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
