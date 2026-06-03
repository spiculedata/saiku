<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import type { SaikuDimension, SaikuMeasure } from "$lib/api/discover";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/DrillthroughModal.js. */
  interface Props {
    dimensions: SaikuDimension[];
    measures: SaikuMeasure[];
    maxRows: number;
    open: boolean;
    onRun: (opts: { dimensions: string[]; measures: string[]; maxRows: number }) => void;
    onExportCsv: (opts: { dimensions: string[]; measures: string[] }) => void;
    onCancel: () => void;
  }

  let { dimensions, measures, maxRows, open, onRun, onExportCsv, onCancel }: Props = $props();
  let pickedDims = $state<Set<string>>(new Set());
  let pickedMeasures = $state<Set<string>>(new Set());
  let rows = $state<number>(untrack(() => maxRows));

  $effect(() => {
    if (open) {
      pickedDims = new Set();
      pickedMeasures = new Set();
      rows = maxRows;
    }
  });

  function toggleDim(un: string) {
    if (pickedDims.has(un)) pickedDims.delete(un);
    else pickedDims.add(un);
    pickedDims = new Set(pickedDims);
  }

  function toggleMeasure(un: string) {
    if (pickedMeasures.has(un)) pickedMeasures.delete(un);
    else pickedMeasures.add(un);
    pickedMeasures = new Set(pickedMeasures);
  }

  function args() {
    return {
      dimensions: Array.from(pickedDims),
      measures: Array.from(pickedMeasures),
      maxRows: rows,
    };
  }
</script>

<Modal title={i18n.t("modal.drillthrough.title")} {open} size="lg" onClose={onCancel}>
  <div class="cols">
    <section>
      <h3>{i18n.t("panels.dimensions")}</h3>
      <ul class="list">
        {#each dimensions as d}
          <li>
            <label>
              <input type="checkbox" checked={pickedDims.has(d.uniqueName)} onchange={() => toggleDim(d.uniqueName)} />
              {d.caption || d.name}
            </label>
          </li>
        {/each}
      </ul>
    </section>
    <section>
      <h3>{i18n.t("panels.measures")}</h3>
      <ul class="list">
        {#each measures as m}
          <li>
            <label>
              <input type="checkbox" checked={pickedMeasures.has(m.uniqueName)} onchange={() => toggleMeasure(m.uniqueName)} />
              {m.caption || m.name}
            </label>
          </li>
        {/each}
      </ul>
    </section>
  </div>
  <label class="field">
    <span class="field__label">{i18n.t("modal.drillthrough.maxRows")}</span>
    <input class="field__input" type="number" min="1" bind:value={rows} />
  </label>
  {#snippet footer()}
    <button type="button" class="btn" onclick={onCancel}>{i18n.t("modal.cancel")}</button>
    <button type="button" class="btn" onclick={() => onExportCsv(args())}>{i18n.t("modal.drillthrough.exportCsv")}</button>
    <button type="button" class="btn btn--primary" onclick={() => onRun(args())}>{i18n.t("toolbar.run")}</button>
  {/snippet}
</Modal>

<style>
  .cols { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); margin-bottom: var(--space-4); }
  h3 { margin: 0 0 var(--space-2); font-size: var(--fs-sm); color: var(--fg-muted); text-transform: uppercase; letter-spacing: 0.04em; }
  .list {
    list-style: none;
    margin: 0;
    padding: 0;
    max-height: 40vh;
    overflow: auto;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
  .list li + li { border-top: 1px solid var(--border); }
  .list label {
    display: flex;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-3);
    cursor: pointer;
  }
  .list label:hover { background: var(--bg-subtle); }
</style>
