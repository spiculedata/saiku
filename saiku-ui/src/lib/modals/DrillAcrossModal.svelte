<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import type { SaikuCube } from "$lib/api/discover";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/DrillAcrossModal.js. */
  interface Props {
    targets: SaikuCube[];
    open: boolean;
    onRun: (target: SaikuCube) => void;
    onCancel: () => void;
  }

  let { targets, open, onRun, onCancel }: Props = $props();
  let picked = $state<string>(untrack(() => targets[0]?.uniqueName ?? ""));

  $effect(() => {
    if (open) picked = targets[0]?.uniqueName ?? "";
  });
</script>

<Modal title={i18n.t("modal.drillAcross.title")} {open} size="md" onClose={onCancel}>
  <label class="field">
    <span class="field__label">{i18n.t("modal.drillAcross.target")}</span>
    <select class="field__input" bind:value={picked}>
      {#each targets as c}
        <option value={c.uniqueName}>{c.caption || c.name}</option>
      {/each}
    </select>
  </label>
  {#snippet footer()}
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.cancel")}</Button>
    <Button onclick={() => {
      const t = targets.find((x) => x.uniqueName === picked);
      if (t) onRun(t);
    }}>{i18n.t("toolbar.run")}</Button>
  {/snippet}
</Modal>
