<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import type { SaikuMember } from "$lib/api/discover";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/ParentMemberSelectorModal.js. */
  interface Props {
    members: SaikuMember[];
    open: boolean;
    onSelect: (m: SaikuMember) => void;
    onCancel: () => void;
  }

  let { members, open, onSelect, onCancel }: Props = $props();
  let search = $state("");

  const filtered = $derived(
    members.filter((m) =>
      !search ||
      (m.caption || m.name).toLowerCase().includes(search.toLowerCase()),
    ),
  );
</script>

<Modal title={i18n.t("modal.parentMember.title")} {open} size="md" onClose={onCancel}>
  <input class="field__input" placeholder={i18n.t("modal.parentMember.search")} bind:value={search} />
  <ul class="list">
    {#each filtered as m}
      <li>
        <button type="button" class="row" onclick={() => onSelect(m)}>
          <span class="font-medium">{m.caption || m.name}</span>
          <span class="un">{m.uniqueName}</span>
        </button>
      </li>
    {/each}
  </ul>
  {#snippet footer()}
    <Button variant="outline" onclick={onCancel}>{i18n.t("modal.close")}</Button>
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
  .row {
    display: flex;
    flex-direction: column;
    align-items: start;
    width: 100%;
    padding: var(--space-2) var(--space-3);
    background: transparent;
    border: 0;
    color: var(--fg);
    font: inherit;
    cursor: pointer;
    text-align: left;
  }
  .row:hover { background: var(--bg-subtle); }
  .un { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--fg-subtle); }
</style>
