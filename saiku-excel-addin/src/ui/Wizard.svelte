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
    padding: 8px 10px;
    background: #f3f2f1;
    border-bottom: 1px solid #edebe9;
    flex-shrink: 0;
    overflow-x: auto;
    gap: 0;
  }

  .step-btn {
    font-family: Segoe UI, system-ui, sans-serif;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    background: none;
    border: none;
    cursor: pointer;
    padding: 4px 6px;
    border-radius: 4px;
    flex-shrink: 0;
  }

  .step-btn:disabled { cursor: default; }
  .step-btn.active .step-num { background: #0078d4; color: #fff; }
  .step-btn.done .step-num { background: #107c10; color: #fff; }

  .step-num {
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background: #e1dfdd;
    color: #605e5c;
    font-size: 11px;
    font-weight: 700;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .step-label {
    font-size: 10px;
    color: #605e5c;
    white-space: nowrap;
  }

  .step-btn.active .step-label { color: #0078d4; font-weight: 600; }
  .step-btn.done .step-label { color: #107c10; }

  .step-sep {
    flex: 1;
    height: 1px;
    background: #c8c6c4;
    min-width: 4px;
    margin: 0 2px;
    margin-bottom: 14px;
  }

  .step-content {
    flex: 1;
    overflow-y: auto;
    padding: 12px 16px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .loading { font-size: 13px; color: #605e5c; padding: 12px 0; }

  .error {
    font-size: 13px;
    color: #a4262c;
    padding: 6px 8px;
    background: #fde7e9;
    border-radius: 2px;
  }

  .insert-error { margin-top: 8px; }

  .nav {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 16px;
    border-top: 1px solid #edebe9;
    background: #fff;
    flex-shrink: 0;
  }

  .nav-right {
    display: flex;
    gap: 6px;
    align-items: center;
  }

  .btn-primary {
    font-family: Segoe UI, system-ui, sans-serif;
    font-size: 13px;
    font-weight: 600;
    color: #fff;
    background: #0078d4;
    border: none;
    border-radius: 2px;
    padding: 6px 14px;
    cursor: pointer;
  }

  .btn-primary:hover:not(:disabled) { background: #106ebe; }
  .btn-primary:disabled { background: #c8c6c4; cursor: default; }

  .btn-secondary {
    font-family: Segoe UI, system-ui, sans-serif;
    font-size: 13px;
    color: #323130;
    background: #fff;
    border: 1px solid #8a8886;
    border-radius: 2px;
    padding: 6px 14px;
    cursor: pointer;
  }

  .btn-secondary:hover:not(:disabled) { background: #f3f2f1; }
  .btn-secondary:disabled { color: #a19f9d; cursor: default; }

  .btn-ghost {
    font-family: Segoe UI, system-ui, sans-serif;
    font-size: 13px;
    color: #605e5c;
    background: none;
    border: none;
    cursor: pointer;
    padding: 6px 4px;
    text-decoration: underline;
  }

  .btn-ghost:hover { color: #323130; }

  .btn-insert { min-width: 80px; }
</style>
