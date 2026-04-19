<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import type { SaikuCube } from "$lib/api/discover";

  /** Port of saiku-ui-legacy/js/saiku/views/DrillAcrossModal.js. */
  interface Props {
    targets: SaikuCube[];
    open: boolean;
    onRun: (target: SaikuCube) => void;
    onCancel: () => void;
  }

  let { targets, open, onRun, onCancel }: Props = $props();
  let picked = $state<string>(targets[0]?.uniqueName ?? "");

  $effect(() => {
    if (open) picked = targets[0]?.uniqueName ?? "";
  });
</script>

<Modal title="Drill across" {open} size="md" onClose={onCancel}>
  <label class="field">
    <span class="field__label">Target cube</span>
    <select class="field__input" bind:value={picked}>
      {#each targets as c}
        <option value={c.uniqueName}>{c.caption || c.name}</option>
      {/each}
    </select>
  </label>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>Cancel</button>
    <button type="button" class="btn btn--primary" onclick={() => {
      const t = targets.find((x) => x.uniqueName === picked);
      if (t) onRun(t);
    }}>Run</button>
  {/snippet}
</Modal>
