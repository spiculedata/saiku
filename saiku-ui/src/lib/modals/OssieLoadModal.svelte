<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import RepositoryBrowser from "$lib/components/RepositoryBrowser.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  /**
   * Load-a-saved-Ossie-query modal. Reuses the shared RepositoryBrowser in "load" mode so
   * the folder-navigation UX matches the MDX SavedQueriesModal (users learn one browser and
   * use it in both flavours). Kept smaller than SavedQueriesModal because the per-file
   * action set (rename / permissions / delete) belongs to the shared repository ops, not
   * this modal's responsibility.
   */
  interface Props {
    open: boolean;
    onOpen: (path: string) => void;
    onCancel: () => void;
    /** Optional initial path — usually the currently-open file so the browser jumps to its folder. */
    initialPath?: string;
  }

  import { untrack } from "svelte";

  let { open, onOpen, onCancel, initialPath = "" }: Props = $props();

  // Seed selection lazily. untrack() prevents Svelte 5 from tracking `initialPath` on
  // this initial read; the $effect below re-seeds every time the modal opens, which is
  // the intended behaviour (open the modal → cursor lands on the current file).
  let selected = $state<string>(untrack(() => initialPath));

  $effect(() => {
    if (open) selected = initialPath;
  });

  const valid = $derived(selected.trim().length > 0 && selected.endsWith(".saiku"));
</script>

<Modal title="Open Ossie query" {open} size="lg" onClose={onCancel}>
  <p class="intro">Pick a saved <code>.saiku</code> file to open.</p>
  <RepositoryBrowser
    mode="load"
    selectedPath={selected}
    onSelect={(p) => (selected = p)}
    onOpen={(p) => onOpen(p)}
  />

  {#snippet footer()}
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.cancel")}</Button>
    <Button disabled={!valid} onclick={() => onOpen(selected)}>Open</Button>
  {/snippet}
</Modal>

<style>
  .intro {
    margin: 0 0 var(--space-3);
    color: hsl(var(--fg-muted));
    font-size: var(--fs-sm);
  }
</style>
