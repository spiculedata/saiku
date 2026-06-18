<script lang="ts">
  import { onMount } from "svelte";
  import { getSchema } from "../lib/discovery";
  import { buildRequest } from "../lib/queryModel";
  import { runQuery } from "../lib/executor";
  import { writeTable, buildPivot } from "../lib/sheetWriter";
  import { saveQuery, listQueries } from "../lib/queryStore";
  import type { SavedQuery } from "../lib/queryStore";
  import type { CubeSchema, WizardState, DimPick, FilterPick, AiQueryRequest } from "../lib/types";
  import CubeStep from "./steps/CubeStep.svelte";
  import MeasuresStep from "./steps/MeasuresStep.svelte";
  import DimensionsStep from "./steps/DimensionsStep.svelte";
  import FiltersStep from "./steps/FiltersStep.svelte";
  import ReviewStep from "./steps/ReviewStep.svelte";

  const {
    initial = null,
    onDone,
    onCancel,
  }: {
    initial?: SavedQuery | null;
    onDone: () => void;
    onCancel: () => void;
  } = $props();

  const STEP_LABELS = ["Cube", "Measures", "Dimensions", "Filters", "Review"];

  function rehydrateWiz(req: AiQueryRequest): WizardState {
    const filters: FilterPick[] = (req.filters ?? []).map((f) => ({
      dimension: f.dimension,
      hierarchy: f.hierarchy,
      level: f.level,
      members: f.members,
    }));
    return {
      cubeId: req.cube,
      measures: req.measures.map((m) => m.name),
      dimensions: req.rows,
      filters,
    };
  }

  function makeInitialWiz(): WizardState {
    if (initial) return rehydrateWiz(initial.request);
    return { cubeId: "", measures: [], dimensions: [], filters: [] };
  }

  let wiz: WizardState = $state(makeInitialWiz());
  let step: number = $state(1);
  let cubeSchema: CubeSchema | null = $state(null);
  let schemaLoading: boolean = $state(false);
  let schemaError: string | null = $state(null);
  let inserting: boolean = $state(false);
  let insertError: string | null = $state(null);

  onMount(async () => {
    if (initial && initial.request.cube) {
      await loadSchema(initial.request.cube);
    }
  });

  async function loadSchema(id: string): Promise<void> {
    schemaLoading = true;
    schemaError = null;
    try {
      cubeSchema = await getSchema(id);
    } catch (e) {
      schemaError = e instanceof Error ? e.message : "Failed to load schema";
    } finally {
      schemaLoading = false;
    }
  }

  async function handleCubeSelect(id: string): Promise<void> {
    wiz = { ...wiz, cubeId: id, measures: [], dimensions: [], filters: [] };
    cubeSchema = null;
    await loadSchema(id);
  }

  function toggleMeasure(name: string): void {
    const has = wiz.measures.includes(name);
    wiz = {
      ...wiz,
      measures: has ? wiz.measures.filter((m) => m !== name) : [...wiz.measures, name],
    };
  }

  function toggleDimension(pick: DimPick): void {
    const exists = wiz.dimensions.some(
      (d) => d.dimension === pick.dimension && d.hierarchy === pick.hierarchy && d.level === pick.level
    );
    let dims: DimPick[];
    let fils: FilterPick[];
    if (exists) {
      dims = wiz.dimensions.filter(
        (d) => !(d.dimension === pick.dimension && d.hierarchy === pick.hierarchy && d.level === pick.level)
      );
      fils = wiz.filters.filter(
        (f) => !(f.dimension === pick.dimension && f.hierarchy === pick.hierarchy && f.level === pick.level)
      );
    } else {
      dims = [...wiz.dimensions, pick];
      fils = wiz.filters;
    }
    wiz = { ...wiz, dimensions: dims, filters: fils };
  }

  function updateFilters(newFilters: FilterPick[]): void {
    wiz = { ...wiz, filters: newFilters };
  }

  function canNext(): boolean {
    if (step === 1) return !!wiz.cubeId && !!cubeSchema && !schemaLoading;
    if (step === 2) return wiz.measures.length > 0;
    if (step === 3) return wiz.dimensions.length > 0;
    return true;
  }

  function goNext(): void {
    if (step < 5 && canNext()) step += 1;
  }

  function goBack(): void {
    if (step > 1) step -= 1;
  }

  function goToStep(s: number): void {
    step = s;
  }

  async function handleInsert(): Promise<void> {
    if (!cubeSchema) return;
    inserting = true;
    insertError = null;

    try {
      const req = buildRequest(wiz);
      const r = await runQuery(req);

      if (!r.ok) {
        insertError = r.error;
        if (r.field?.startsWith("rows")) {
          step = 3;
        } else if (r.field?.startsWith("filters")) {
          step = 4;
        } else if (r.field?.startsWith("measures")) {
          step = 2;
        }
        return;
      }

      let sheetName: string;
      if (initial?.sheet) {
        sheetName = initial.sheet;
      } else {
        const existing = await listQueries();
        sheetName = `Saiku Query ${existing.length + 1}`;
      }

      const tableName = await writeTable(sheetName, r.grid);
      await buildPivot(sheetName, tableName);

      const cubePart = wiz.cubeId.split("/").pop() ?? wiz.cubeId;
      const measuresSummary =
        wiz.measures.slice(0, 2).join(", ") + (wiz.measures.length > 2 ? "…" : "");
      const title = `${cubePart}: ${measuresSummary}`;

      await saveQuery({
        id: initial?.id ?? crypto.randomUUID(),
        title,
        sheet: sheetName,
        request: req,
      });

      onDone();
    } catch (e) {
      insertError = e instanceof Error ? e.message : "Insert failed";
    } finally {
      inserting = false;
    }
  }
</script>

<div class="wizard">
  <!-- Stepper header -->
  <div class="stepper" role="tablist">
    {#each STEP_LABELS as label, i (label)}
      {@const s = i + 1}
      <button
        class="step-btn"
        class:done={s < step}
        class:active={s === step}
        role="tab"
        aria-selected={s === step}
        disabled={s > step || (s > 1 && !wiz.cubeId)}
        onclick={() => { if (s <= step) goToStep(s); }}
      >
        <span class="step-num">
          {#if s < step}✓{:else}{s}{/if}
        </span>
        <span class="step-label">{label}</span>
      </button>
      {#if i < STEP_LABELS.length - 1}
        <div class="step-sep"></div>
      {/if}
    {/each}
  </div>

  <!-- Step content -->
  <div class="step-content">
    {#if schemaLoading && step > 1}
      <div class="loading">Loading schema…</div>
    {:else if schemaError && step > 1}
      <div class="error" role="alert">{schemaError}</div>
    {:else if step === 1}
      <CubeStep selectedCubeId={wiz.cubeId} onSelect={handleCubeSelect} />
    {:else if step === 2 && cubeSchema}
      <MeasuresStep schema={cubeSchema} selected={wiz.measures} onToggle={toggleMeasure} />
    {:else if step === 3 && cubeSchema}
      <DimensionsStep schema={cubeSchema} selected={wiz.dimensions} onToggle={toggleDimension} />
    {:else if step === 4}
      <FiltersStep
        cubeId={wiz.cubeId}
        dimensions={wiz.dimensions}
        filters={wiz.filters}
        onUpdate={updateFilters}
      />
    {:else if step === 5 && cubeSchema}
      <ReviewStep wizState={wiz} schema={cubeSchema} onEditStep={goToStep} />
    {:else if !cubeSchema && step > 1}
      <div class="error" role="alert">Schema not loaded. Go back to step 1 and select a cube.</div>
    {/if}

    {#if insertError}
      <div class="error insert-error" role="alert">{insertError}</div>
    {/if}
  </div>

  <!-- Navigation -->
  <div class="nav">
    <button class="btn-ghost" onclick={onCancel}>Cancel</button>
    <div class="nav-right">
      {#if step > 1}
        <button class="btn-secondary" onclick={goBack} disabled={inserting}>Back</button>
      {/if}
      {#if step < 5}
        <button class="btn-primary" onclick={goNext} disabled={!canNext()}>Next</button>
      {:else}
        <button class="btn-primary btn-insert" onclick={handleInsert} disabled={inserting}>
          {#if inserting}Inserting…{:else}Insert{/if}
        </button>
      {/if}
    </div>
  </div>
</div>

<style>
  .wizard {
    display: flex;
    flex-direction: column;
    height: 100vh;
    padding: 0;
    box-sizing: border-box;
  }

  .stepper {
    display: flex;
    align-items: center;
    padding: var(--space-2) 10px;
    background: var(--bg-muted);
    border-bottom: 1px solid var(--border);
    flex-shrink: 0;
    overflow-x: auto;
    gap: 0;
  }

  .step-btn {
    font-family: var(--font);
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    background: none;
    border: none;
    cursor: pointer;
    padding: var(--space-1) 6px;
    border-radius: var(--radius-sm);
    flex-shrink: 0;
  }

  .step-btn:disabled { cursor: default; }
  .step-btn.active .step-num { background: var(--accent); color: var(--on-accent); }
  .step-btn.done .step-num { background: var(--success); color: var(--on-accent); }

  .step-num {
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background: var(--bg-subtle);
    color: var(--fg-subtle);
    font-size: var(--fs-xs);
    font-weight: var(--fw-bold);
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .step-label {
    font-size: 10px;
    color: var(--fg-muted);
    white-space: nowrap;
  }

  .step-btn.active .step-label { color: var(--accent); font-weight: var(--fw-semibold); }
  .step-btn.done .step-label { color: var(--success); }

  .step-sep {
    flex: 1;
    height: 1px;
    background: var(--border-strong);
    min-width: 4px;
    margin: 0 2px;
    margin-bottom: 14px;
  }

  .step-content {
    flex: 1;
    overflow-y: auto;
    padding: var(--space-3) var(--space-4);
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  .loading { font-size: var(--fs-sm); color: var(--fg-muted); padding: var(--space-3) 0; }

  .error {
    font-size: var(--fs-sm);
    color: var(--danger);
    padding: 6px 8px;
    background: var(--danger-soft);
    border-radius: var(--radius-sm);
  }

  .insert-error { margin-top: var(--space-2); }

  .nav {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px var(--space-4);
    border-top: 1px solid var(--border);
    background: var(--bg);
    flex-shrink: 0;
  }

  .nav-right {
    display: flex;
    gap: 6px;
    align-items: center;
  }

  .btn-primary {
    font-family: var(--font);
    font-size: var(--fs-sm);
    font-weight: var(--fw-semibold);
    color: var(--on-accent);
    background: var(--accent);
    border: none;
    border-radius: var(--radius-sm);
    padding: 6px 14px;
    cursor: pointer;
    box-shadow: var(--shadow-sm);
  }

  .btn-primary:hover:not(:disabled) { background: var(--accent-hover); }
  .btn-primary:disabled { background: var(--border-strong); cursor: default; }

  .btn-secondary {
    font-family: var(--font);
    font-size: var(--fs-sm);
    color: var(--fg);
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    padding: 6px 14px;
    cursor: pointer;
  }

  .btn-secondary:hover:not(:disabled) { background: var(--bg-muted); }
  .btn-secondary:disabled { color: var(--fg-subtle); cursor: default; }

  .btn-ghost {
    font-family: var(--font);
    font-size: var(--fs-sm);
    color: var(--fg-muted);
    background: none;
    border: none;
    cursor: pointer;
    padding: 6px var(--space-1);
    text-decoration: underline;
  }

  .btn-ghost:hover { color: var(--fg); }

  .btn-insert { min-width: 80px; }
</style>
