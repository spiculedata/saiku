<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import MonacoEditor from "$lib/components/MonacoEditor.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/MDXModal.js — Monaco-backed. */
  interface Props {
    mdx: string;
    open: boolean;
    onRun: (mdx: string) => void;
    onCancel: () => void;
  }

  let { mdx, open, onRun, onCancel }: Props = $props();
  let buffer = $state<string>(untrack(() => mdx));

  $effect(() => {
    if (open) buffer = mdx;
  });

  function copy() {
    navigator.clipboard?.writeText(buffer);
  }
</script>

<Modal title={i18n.t("toolbar.mdx", "MDX query")} {open} size="xl" onClose={onCancel}>
  <div class="field__label">MDX</div>
  {#if open}
    <MonacoEditor value={buffer} language="mdx" minHeight="320px" onChange={(v) => (buffer = v)} />
  {/if}
  {#snippet footer()}
    <Button variant="outline" onclick={copy}>{i18n.t("modal.mdx.copy")}</Button>
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.close")}</Button>
    <Button >onRun(buffer)}>{i18n.t("modal.mdx.run")}</Button>
  {/snippet}
</Modal>
