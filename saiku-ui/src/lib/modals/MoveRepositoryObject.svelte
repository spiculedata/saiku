<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/MoveRepositoryObject.js. */
  interface Props {
    sourcePath: string;
    folders: string[];
    open: boolean;
    onMove: (targetFolder: string) => void;
    onCancel: () => void;
  }

  let { sourcePath, folders, open, onMove, onCancel }: Props = $props();
  let target = $state<string>(untrack(() => folders[0] ?? ""));
</script>

<Modal title={i18n.t("modal.move.title")} {open} size="md" onClose={onCancel}>
  <p class="hint">{i18n.t("modal.move.prompt")} <code>{sourcePath}</code>.</p>
  <label class="field">
    <span class="field__label">{i18n.t("modal.move.destination")}</span>
    <select class="field__input" bind:value={target}>
      {#each folders as f}
        <option value={f}>{f || "/"}</option>
      {/each}
    </select>
  </label>
  {#snippet footer()}
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.cancel")}</Button>
    <Button >onMove(target)}>{i18n.t("modal.move.action")}</Button>
  {/snippet}
</Modal>

<style>
  .hint { color: var(--fg-muted); font-size: var(--fs-sm); margin: 0 0 var(--space-3); }
  code {
    background: var(--bg-subtle);
    padding: 0 var(--space-1);
    border-radius: var(--radius-sm);
    font-family: var(--font-mono);
    font-size: 12px;
  }
</style>
