<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import RepositoryBrowser from "$lib/components/RepositoryBrowser.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  /*
   * Save-query dialog.
   *
   * Uses the shared RepositoryBrowser in "save" mode — user navigates
   * folders, optionally creates a new one inline, and the selected
   * folder becomes the target. A separate "Name" input below collects
   * the filename. The save endpoint mkdirs missing parent folders at
   * write time so any path the browser shows can be written into.
   *
   * Replaces the previous combo-input that didn't give users a real
   * sense of where their saved queries lived.
   */
  interface Props {
    defaultName: string;
    defaultFolder: string;
    folders: string[];
    open: boolean;
    onSave: (folder: string, name: string) => void;
    onCancel: () => void;
  }

  let { defaultName, defaultFolder, open, onSave, onCancel }: Props = $props();

  let name = $state<string>(untrack(() => defaultName));
  let folder = $state<string>(untrack(() => defaultFolder));

  $effect(() => {
    if (open) {
      name = defaultName;
      folder = defaultFolder;
    }
  });

  function normalizeFolder(raw: string): string {
    return raw.replace(/^\/+|\/+$/g, "").replace(/\/{2,}/g, "/");
  }

  const valid = $derived(name.trim().length > 0);

  function save(): void {
    onSave(normalizeFolder(folder), name.trim());
  }

  // Focus the Name input as soon as it mounts. The modal body only
  // renders while `open` is true, so the action runs on every open. A
  // queueMicrotask defers the focus until after the node is painted,
  // and — being outside any $effect — it never re-triggers reactivity.
  function autofocus(node: HTMLInputElement) {
    queueMicrotask(() => node.focus());
  }

  function onNameKeydown(e: KeyboardEvent) {
    if (e.key === "Enter" && valid) {
      e.preventDefault();
      save();
    }
  }
</script>

<Modal title={i18n.t("modal.save.title")} {open} size="lg" onClose={onCancel}>
  <p class="save-modal__intro">
    {i18n.t("modal.save.intro").replace("{folder}", folder || i18n.t("repo.root"))}
  </p>

  <label class="field">
    <span class="field__label">{i18n.t("modal.save.name")}</span>
    <input
      class="field__input"
      bind:value={name}
      autocomplete="off"
      required
      use:autofocus
      onkeydown={onNameKeydown}
    />
  </label>

  <div class="mt-3">
    <RepositoryBrowser
      mode="save"
      selectedPath={folder}
      onSelect={(p) => (folder = p)}
    />
  </div>

  {#snippet footer()}
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.cancel")}</Button>
    <Button
      disabled={!valid}
      onclick={save}
    >{i18n.t("modal.save")}</Button>
  {/snippet}
</Modal>

<style>
.save-modal__intro {
    margin: 0 0 var(--space-3);
    color: var(--fg-muted);
    font-size: var(--fs-sm);
  }
</style>
