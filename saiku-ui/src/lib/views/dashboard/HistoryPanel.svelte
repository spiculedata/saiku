<script lang="ts">
  /*
   * #947 PR2 — dashboard version history. Lists archived versions (newest
   * first), opens a full read-only preview of one (in a new tab so it can't
   * disturb the editing tab), and restores one (which itself archives the
   * current state, so restore is reversible).
   */
  import { base } from "$app/paths";
  import Modal from "$lib/components/Modal.svelte";
  import { RotateCcw, ExternalLink } from "lucide-svelte";
  import { getHistory, restoreHistory, type DashboardHistoryEntry } from "$lib/api/dashboards";
  import { buildHistoryPreviewUrl } from "$lib/dashboard/historyPreview";

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

  $effect(() => {
    if (open) {
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

  /** Open a full read-only render of the version in a new tab (isolated store). */
  function openPreview(version: string): void {
    const origin = typeof window !== "undefined" ? window.location.origin : "";
    const url = buildHistoryPreviewUrl(dashboardPath, version, { origin, base });
    window.open(url, "_blank", "noopener");
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
            </div>
            <div class="hist__actions">
              <button
                type="button"
                class="btn btn--sm"
                onclick={() => openPreview(v.version)}
                title="Open a read-only preview in a new tab"
              >
                <ExternalLink size={13} /><span>Preview</span>
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
  .hist__actions {
    display: flex;
    gap: var(--space-2);
  }
</style>
