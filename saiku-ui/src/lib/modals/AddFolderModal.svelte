<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/AddFolderModal.js. */
  interface Props {
    parentPath: string;
    open: boolean;
    onCreate: (name: string) => void;
    onCancel: () => void;
  }

  let { parentPath, open, onCreate, onCancel }: Props = $props();
  let name = $state("");

  function submit(e: Event) {
    e.preventDefault();
    if (name.trim()) onCreate(name.trim());
  }
</script>

<Modal title={i18n.t("modal.addFolder.title")} {open} size="sm" onClose={onCancel}>
  <form onsubmit={submit}>
    <p class="hint">{i18n.t("modal.addFolder.under")} <code>{parentPath || "/"}</code>.</p>
    <label class="field">
      <span class="field__label">{i18n.t("modal.addFolder.name")}</span>
      <input class="field__input" bind:value={name} autocomplete="off" required />
    </label>
  </form>
  {#snippet footer()}
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.cancel")}</Button>
    <Button disabled={!name.trim()}>onCreate(name.trim())}
    >{i18n.t("modal.save")}</Button>
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
