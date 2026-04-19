<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/CalculatedMemberModal.js. */
  export interface CalculatedMember {
    name: string;
    parent: string;
    formula: string;
    formatString: string;
    dimension?: string;
  }

  interface Props {
    initial?: CalculatedMember;
    hierarchies: string[];
    open: boolean;
    onSave: (m: CalculatedMember) => void;
    onCancel: () => void;
  }

  let {
    initial = { name: "", parent: "", formula: "", formatString: "#,##0.00" },
    hierarchies,
    open,
    onSave,
    onCancel,
  }: Props = $props();

  let form = $state<CalculatedMember>({ ...initial });

  $effect(() => {
    if (open) form = { ...initial };
  });

  const valid = $derived(form.name.trim() && form.formula.trim());
</script>

<Modal title="Calculated member" {open} size="lg" onClose={onCancel}>
  <label class="field">
    <span class="field__label">Name</span>
    <input class="field__input" bind:value={form.name} />
  </label>
  <label class="field">
    <span class="field__label">Parent hierarchy</span>
    <select class="field__input" bind:value={form.parent}>
      {#each hierarchies as h}
        <option value={h}>{h}</option>
      {/each}
    </select>
  </label>
  <label class="field">
    <span class="field__label">Formula (MDX)</span>
    <textarea class="field__input mdx" bind:value={form.formula} rows="8" spellcheck="false"></textarea>
  </label>
  <label class="field">
    <span class="field__label">Format string</span>
    <input class="field__input" bind:value={form.formatString} />
  </label>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>Cancel</button>
    <button type="button" class="btn btn--primary" disabled={!valid} onclick={() => onSave(form)}>Save</button>
  {/snippet}
</Modal>

<style>
  .mdx { font-family: var(--font-mono); font-size: var(--fs-sm); resize: vertical; }
</style>
