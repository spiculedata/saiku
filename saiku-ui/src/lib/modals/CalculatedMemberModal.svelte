<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import MonacoEditor from "$lib/components/MonacoEditor.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

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
  <div class="field">
    <span class="field__label">Formula (MDX)</span>
    {#if open}
      <MonacoEditor value={form.formula} language="mdx" minHeight="200px" onChange={(v) => (form.formula = v)} />
    {/if}
  </div>
  <label class="field">
    <span class="field__label">Format string</span>
    <input class="field__input" bind:value={form.formatString} />
  </label>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>{i18n.t("modal.cancel")}</button>
    <button type="button" class="btn btn--primary" disabled={!valid} onclick={() => onSave(form)}>{i18n.t("modal.save")}</button>
  {/snippet}
</Modal>
