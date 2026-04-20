<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import {
    deleteSavedQuery,
    listSavedQueries,
    moveSavedQuery,
    readSavedQuery,
    writeSavedQuery,
    type SavedQueryFile,
  } from "$lib/api/repository";
  import type { ThinQuery } from "$lib/api/query";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { FolderOpen, Copy, Pencil, Trash2, Search } from "lucide-svelte";

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
      toasts.danger("Open failed", err instanceof Error ? err.message : String(err));
    }
  }

  async function doDuplicate(entry: SavedQueryFile) {
    try {
      const q = await readSavedQuery(entry.path);
      const idx = entry.path.lastIndexOf("/");
      const folder = idx > 0 ? entry.path.slice(0, idx) : "";
      const baseName = entry.name.replace(/\.saiku$/i, "");
      const newName = `Copy of ${baseName}.saiku`;
      const newPath = folder ? `${folder}/${newName}` : newName;
      await writeSavedQuery(newPath, q);
      toasts.success("Duplicated", newPath);
      await refresh();
    } catch (err) {
      toasts.danger("Duplicate failed", err instanceof Error ? err.message : String(err));
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
      toasts.success("Renamed", newPath);
      renaming = null;
      await refresh();
    } catch (err) {
      toasts.danger("Rename failed", err instanceof Error ? err.message : String(err));
    }
  }

  async function commitDelete() {
    const entry = confirming;
    if (!entry) return;
    try {
      await deleteSavedQuery(entry.path);
      toasts.success("Deleted", entry.path);
      confirming = null;
      await refresh();
    } catch (err) {
      toasts.danger("Delete failed", err instanceof Error ? err.message : String(err));
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
    <p class="hint">{search ? "No matches." : i18n.t("modal.open.empty")}</p>
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
              <button type="button" class="btn btn--primary" onclick={() => commitRename()}>Save</button>
              <button type="button" class="btn" onclick={() => (renaming = null)}>Cancel</button>
            </div>
          {:else}
            <button
              type="button"
              class="saved__main"
              ondblclick={() => doOpen(entry)}
              onkeydown={(e) => onRowKey(e, entry)}
              title="Double-click or press Enter to open"
            >
              <span class="saved__name">{entry.name}</span>
              <span class="saved__path">{entry.path}</span>
            </button>
            <div class="saved__actions">
              <button type="button" class="icon-btn" title="Open" onclick={() => doOpen(entry)}>
                <FolderOpen size={16} />
              </button>
              <button type="button" class="icon-btn" title="Duplicate" onclick={() => doDuplicate(entry)}>
                <Copy size={16} />
              </button>
              <button type="button" class="icon-btn" title="Rename" onclick={() => beginRename(entry)}>
                <Pencil size={16} />
              </button>
              <button
                type="button"
                class="icon-btn icon-btn--danger"
                title="Delete"
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
      <p>Delete <strong>{confirming.name}</strong>? This cannot be undone.</p>
      <div class="saved__confirm-actions">
        <button type="button" class="btn" onclick={() => (confirming = null)}>Cancel</button>
        <button type="button" class="btn btn--danger" onclick={() => commitDelete()}>Delete</button>
      </div>
    </div>
  {/if}

  {#snippet footer()}
    <button type="button" class="btn" onclick={onClose}>{i18n.t("modal.close")}</button>
  {/snippet}
</Modal>

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
  .icon-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    background: transparent;
    border: 1px solid transparent;
    border-radius: 4px;
    color: var(--fg-muted);
    cursor: pointer;
  }
  .icon-btn:hover { background: var(--bg); border-color: var(--border); color: var(--fg); }
  .icon-btn--danger:hover { color: var(--danger); }
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
