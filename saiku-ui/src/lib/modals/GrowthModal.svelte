<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/GrowthModal.js. */
  export type GrowthBasis = "previous" | "first" | "specific";

  interface Props {
    open: boolean;
    onApply: (basis: GrowthBasis, referenceValue?: string) => void;
    onCancel: () => void;
  }

  let { open, onApply, onCancel }: Props = $props();
  let basis = $state<GrowthBasis>("previous");
  let reference = $state<string>("");

  $effect(() => {
    if (open) {
      basis = "previous";
      reference = "";
    }
  });
</script>

<Modal title="Growth" {open} size="md" onClose={onCancel}>
  <fieldset class="field">
    <legend class="field__label">Compare against</legend>
    <label class="radio"><input type="radio" name="basis" value="previous" bind:group={basis} /> Previous period</label>
    <label class="radio"><input type="radio" name="basis" value="first" bind:group={basis} /> First period</label>
    <label class="radio"><input type="radio" name="basis" value="specific" bind:group={basis} /> Specific member</label>
  </fieldset>
  {#if basis === "specific"}
    <label class="field">
      <span class="field__label">Reference member (unique name)</span>
      <input class="field__input" bind:value={reference} />
    </label>
  {/if}
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>Cancel</button>
    <button type="button" class="btn btn--primary" onclick={() => onApply(basis, basis === "specific" ? reference : undefined)}>Apply</button>
  {/snippet}
</Modal>

<style>
  .radio {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) 0;
    cursor: pointer;
  }
</style>
