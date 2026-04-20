<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import type { SaikuMeasure } from "$lib/api/discover";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/MeasuresModal.js. */
  interface Props {
    available: SaikuMeasure[];
    selectedUniqueNames: string[];
    open: boolean;
    onSave: (uniqueNames: string[]) => void;
    onCancel: () => void;
  }

  let { available, selectedUniqueNames, open, onSave, onCancel }: Props = $props();
  let picks = $state<Set<string>>(new Set(selectedUniqueNames));
  let search = $state("");

  $effect(() => {
    if (open) picks = new Set(selectedUniqueNames);
  });

  const filtered = $derived(
    available.filter((m) =>
      !search ||
      (m.caption || m.name).toLowerCase().includes(search.toLowerCase()),
    ),
  );

  function toggle(un: string) {
    if (picks.has(un)) picks.delete(un);
    else picks.add(un);
    picks = new Set(picks);
  }
</script>

<Modal title={i18n.t("panels.measures")} {open} size="md" onClose={onCancel}>
  <input class="field__input" placeholder={i18n.t("modal.calc.filterMeasures")} bind:value={search} />
  <ul class="list">
    {#each filtered as m}
      <li>
        <label>
          <input type="checkbox" checked={picks.has(m.uniqueName)} onchange={() => toggle(m.uniqueName)} />
          <span class="name">{m.caption || m.name}</span>
          {#if m.calculated}<span class="badge">{i18n.t("modal.measures.calcBadge")}</span>{/if}
        </label>
      </li>
    {/each}
  </ul>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>{i18n.t("modal.cancel")}</button>
    <button type="button" class="btn btn--primary" onclick={() => onSave(Array.from(picks))}>{i18n.t("modal.save")}</button>
  {/snippet}
</Modal>

<style>
  .list {
    list-style: none;
    margin: var(--space-3) 0 0;
    padding: 0;
    max-height: 50vh;
    overflow: auto;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
  .list li + li { border-top: 1px solid var(--border); }
  .list label {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    cursor: pointer;
  }
  .list label:hover { background: var(--bg-subtle); }
  .badge {
    font-size: var(--fs-xs);
    color: var(--accent);
    padding: 0 var(--space-1);
    border: 1px solid var(--accent);
    border-radius: var(--radius-sm);
  }
  .name { flex: 1; }
</style>
