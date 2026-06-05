<script lang="ts">
  /*
   * #947 PR2 — dashboard version history. Lists archived versions (newest
   * first), previews a version's summary, and restores one (which itself
   * archives the current state, so restore is reversible).
   */
  import Modal from "$lib/components/Modal.svelte";
  import { RotateCcw, Eye } from "lucide-svelte";
  import {
    getHistory,
    getHistoryVersion,
    restoreHistory,
    type DashboardHistoryEntry,
  } from "$lib/api/dashboards";

  interface Props {
    dashboardPath: string;
    open: boolean;
    onClose: () => void;
    /** Called after a successful restore so the parent can reload the dashboard. */
    onRestored?: () => void;
  }

  let { dashboardPath, open, onClose, onRestored }: Props = $props();

  let versions = $state<DashboardHistoryEntry[]>([]);
  let loading = $state(false);
  let error = $state<string | null>(null);
  let busyVersion = $state<string | null>(null);
  let preview = $state<{ version: string; name: string; tiles: number } | null>(null);

  $effect(() => {
    if (open) {
      preview = null;
      void load();
    }
  });

  async function load(): Promise<void> {
    loading = true;
    error = null;
    try {
      versions = await getHistory(dashboardPath);
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to load history";
    } finally {
      loading = false;
    }
  }

  async function showPreview(version: string): Promise<void> {
    busyVersion = version;
    try {
      const d = await getHistoryVersion(dashboardPath, version);
      preview = { version, name: d.name ?? "(untitled)", tiles: d.layout?.tiles?.length ?? 0 };
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to load version";
    } finally {
      busyVersion = null;
    }
  }

  async function restore(version: string): Promise<void> {
    if (!confirm("Restore this version? The current state is archived first, so this is reversible.")) {
      return;
    }
    busyVersion = version;
    error = null;
    try {
      await restoreHistory(dashboardPath, version);
      onRestored?.();
      onClose();
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to restore";
    } finally {
      busyVersion = null;
    }
  }

  function fmtDate(ms: number): string {
    try {
      return new Date(ms).toLocaleString();
    } catch {
      return String(ms);
    }
  }
</script>

<Modal title="Version history" {open} size="md" {onClose}>
  <div class="hist">
    {#if loading}
      <p class="hist__state">Loading…</p>
    {:else if error}
      <p class="hist__state hist__state--error">{error}</p>
    {:else if versions.length === 0}
      <p class="hist__state">No earlier versions yet. A version is archived each time you save.</p>
    {:else}
      <ul class="hist__list">
        {#each versions as v (v.version)}
          <li class="hist__row">
            <div class="hist__meta">
              <span class="hist__date">{fmtDate(v.createdAt)}</span>
              <span class="hist__author">by {v.author || "unknown"}</span>
              {#if preview && preview.version === v.version}
                <span class="hist__preview">“{preview.name}” · {preview.tiles} tile(s)</span>
              {/if}
            </div>
            <div class="hist__actions">
              <button
                type="button"
                class="btn btn--sm"
                onclick={() => showPreview(v.version)}
                disabled={busyVersion === v.version}
                title="Preview this version"
              >
                <Eye size={13} /><span>Preview</span>
              </button>
              <button
                type="button"
                class="btn btn--sm primary"
                onclick={() => restore(v.version)}
                disabled={busyVersion === v.version}
                title="Restore this version"
              >
                <RotateCcw size={13} /><span>Restore</span>
              </button>
            </div>
          </li>
        {/each}
      </ul>
    {/if}
  </div>
</Modal>

<style>
  .hist {
    min-width: 24rem;
    display: flex;
    flex-direction: column;
  }
  .hist__state {
    color: var(--fg-muted);
    font-size: var(--fs-sm);
    padding: var(--space-3) 0;
  }
  .hist__state--error {
    color: var(--danger);
  }
  .hist__list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
  }
  .hist__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-2) 0;
    border-bottom: 1px solid var(--border);
  }
  .hist__meta {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .hist__date {
    font-size: var(--fs-sm);
    color: var(--fg);
  }
  .hist__author {
    font-size: var(--fs-xs, 0.75rem);
    color: var(--fg-muted);
  }
  .hist__preview {
    font-size: var(--fs-xs, 0.75rem);
    color: var(--accent);
  }
  .hist__actions {
    display: flex;
    gap: var(--space-2);
  }
</style>
