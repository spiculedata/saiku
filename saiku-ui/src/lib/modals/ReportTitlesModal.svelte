<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/ReportTitlesModal.js. */
  export interface ReportTitles {
    title: string;
    subtitle: string;
    notes: string;
  }

  interface Props {
    titles: ReportTitles;
    open: boolean;
    onSave: (t: ReportTitles) => void;
    onCancel: () => void;
  }

  let { titles, open, onSave, onCancel }: Props = $props();
  let form = $state<ReportTitles>({ ...titles });

  $effect(() => {
    if (open) form = { ...titles };
  });
</script>

<Modal title="Report titles" {open} size="md" onClose={onCancel}>
  <label class="field">
    <span class="field__label">Title</span>
    <input class="field__input" bind:value={form.title} />
  </label>
  <label class="field">
    <span class="field__label">Subtitle</span>
    <input class="field__input" bind:value={form.subtitle} />
  </label>
  <label class="field">
    <span class="field__label">Notes</span>
    <textarea class="field__input" rows="4" bind:value={form.notes}></textarea>
  </label>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>Cancel</button>
    <button type="button" class="btn btn--primary" onclick={() => onSave(form)}>Save</button>
  {/snippet}
</Modal>
