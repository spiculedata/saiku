<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import type { SaikuMember } from "$lib/api/discover";

  /** Port of saiku-ui-legacy/js/saiku/views/SelectionsModal.js — member
   * selections for a level, with include/exclude + search + show-unique. */
  export type SelectionType = "INCLUSION" | "EXCLUSION";

  interface Props {
    levelCaption: string;
    available: SaikuMember[];
    initialSelected: string[];
    initialType: SelectionType;
    open: boolean;
    onSave: (uniqueNames: string[], type: SelectionType) => void;
    onOpenDateFilter: () => void;
    onCancel: () => void;
  }

  let {
    levelCaption,
    available,
    initialSelected,
    initialType,
    open,
    onSave,
    onOpenDateFilter,
    onCancel,
  }: Props = $props();

  let search = $state("");
  let selected = $state<Set<string>>(new Set(initialSelected));
  let type = $state<SelectionType>(initialType);

  $effect(() => {
    if (open) {
      selected = new Set(initialSelected);
      type = initialType;
      search = "";
    }
  });

  const filtered = $derived(
    available.filter((m) =>
      !search ||
      (m.caption || m.name).toLowerCase().includes(search.toLowerCase()),
    ),
  );

  function toggle(un: string) {
    if (selected.has(un)) selected.delete(un);
    else selected.add(un);
    selected = new Set(selected);
  }

  function selectAll() {
    selected = new Set(filtered.map((m) => m.uniqueName));
  }

  function clear() {
    selected = new Set();
  }
</script>

<Modal title={`Selections for ${levelCaption}`} {open} size="lg" onClose={onCancel}>
  <div class="row">
    <label class="field field--grow">
      <span class="field__label">Filter members</span>
      <input class="field__input" bind:value={search} placeholder="Search by name or caption" />
    </label>
    <label class="field">
      <span class="field__label">Mode</span>
      <select class="field__input" bind:value={type}>
        <option value="INCLUSION">Include</option>
        <option value="EXCLUSION">Exclude</option>
      </select>
    </label>
  </div>
  <div class="bar">
    <button type="button" class="btn" onclick={selectAll}>Select all</button>
    <button type="button" class="btn" onclick={clear}>Clear</button>
    <span class="count">{selected.size} selected</span>
  </div>
  <ul class="members">
    {#each filtered as m}
      <li>
        <label>
          <input type="checkbox" checked={selected.has(m.uniqueName)} onchange={() => toggle(m.uniqueName)} />
          <span class="name">{m.caption || m.name}</span>
          {#if m.description}<span class="desc">{m.description}</span>{/if}
        </label>
      </li>
    {/each}
    {#if filtered.length === 0}
      <li class="empty">No members match.</li>
    {/if}
  </ul>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onOpenDateFilter}>Open date filter</button>
    <button type="button" class="btn" onclick={onCancel}>Cancel</button>
    <button type="button" class="btn btn--primary" onclick={() => onSave(Array.from(selected), type)}>OK</button>
  {/snippet}
</Modal>

<style>
  .row { display: flex; gap: var(--space-3); align-items: end; }
  .field--grow { flex: 1; }
  .bar {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin: var(--space-3) 0;
  }
  .count { margin-left: auto; color: var(--fg-muted); font-size: var(--fs-sm); }
  .members {
    list-style: none;
    margin: 0;
    padding: 0;
    max-height: 45vh;
    overflow: auto;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
  .members li + li { border-top: 1px solid var(--border); }
  .members li.empty { padding: var(--space-4); color: var(--fg-muted); text-align: center; }
  .members label {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    cursor: pointer;
  }
  .members label:hover { background: var(--bg-subtle); }
  .name { flex: 1; }
  .desc { color: var(--fg-subtle); font-size: var(--fs-xs); }
</style>
