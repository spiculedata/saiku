<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/OpenDialog.js. Expects a flat
   * list from /rest/saiku/api/repository for the first slice. The tree
   * view arrives in the Repository integration slice. */
  export interface RepoEntry {
    path: string;
    name: string;
    type: "folder" | "file";
    fileType?: string;
  }

  interface Props {
    entries: RepoEntry[];
    loading: boolean;
    open: boolean;
    onSelect: (entry: RepoEntry) => void;
    onCancel: () => void;
  }

  let { entries, loading, open, onSelect, onCancel }: Props = $props();
  let search = $state("");

  const filtered = $derived(
    entries.filter(
      (e) =>
        !search ||
        e.name.toLowerCase().includes(search.toLowerCase()) ||
        e.path.toLowerCase().includes(search.toLowerCase()),
    ),
  );
</script>

<Modal title={i18n.t("modal.open.title")} {open} size="lg" onClose={onCancel}>
  <label class="field">
    <span class="field__label">{i18n.t("modal.open.filter")}</span>
    <input
      class="field__input"
      bind:value={search}
      placeholder={i18n.t("modal.open.searchPlaceholder")}
    />
  </label>
  {#if loading}
    <p class="hint">{i18n.t("modal.open.loading")}</p>
  {:else if entries.length === 0}
    <p class="hint">{i18n.t("modal.open.empty")}</p>
  {:else}
    <ul class="repo">
      {#each filtered as entry}
        <li class="repo__entry">
          <button
            type="button"
            class="repo__row"
            disabled={entry.type === "folder"}
            onclick={() => onSelect(entry)}
          >
            <span class="repo__icon">{entry.type === "folder" ? "📁" : "📄"}</span>
            <span class="repo__name">{entry.name}</span>
            <span class="repo__path">{entry.path}</span>
          </button>
        </li>
      {/each}
    </ul>
  {/if}
  {#snippet footer()}
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.close")}</Button>
  {/snippet}
</Modal>

<style>
  .hint { color: var(--fg-muted); font-size: var(--fs-sm); margin: var(--space-2) 0; }
  .repo {
    list-style: none;
    margin: 0;
    padding: 0;
    max-height: 40vh;
    overflow: auto;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
  .repo__entry + .repo__entry { border-top: 1px solid var(--border); }
  .repo__row {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    width: 100%;
    padding: var(--space-2) var(--space-3);
    background: transparent;
    border: 0;
    color: var(--fg);
    font: inherit;
    cursor: pointer;
    text-align: left;
  }
  .repo__row:hover:not(:disabled) { background: var(--bg-subtle); }
  .repo__row:disabled { color: var(--fg-muted); cursor: default; }
  .repo__icon { width: 20px; text-align: center; }
  .repo__name { flex: 1; }
  .repo__path { color: var(--fg-subtle); font-size: var(--fs-sm); }
</style>
