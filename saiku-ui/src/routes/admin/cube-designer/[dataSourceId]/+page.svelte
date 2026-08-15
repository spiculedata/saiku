<!--
  Admin › Cube Designer — the OSS home for the visual cube/schema designer
  ported from saiku-cloud. Dedicated dynamic route (mirrors the schema-generator)
  so the heavy canvas app gets a full page + a datasource context.

  This route is the HOST: it creates the store, provides the OSS backend via
  context (setCubeDesignerBackend), profiles the datasource into the source
  sidebar, and owns Save (emit Mondrian 4 XML → admin schema upload). The
  designer components themselves are host-agnostic.
-->
<script lang="ts">
  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { SchemaCanvasStore } from "$lib/cube-designer/state.svelte";
  import SchemaCanvasView from "$lib/cube-designer/SchemaCanvasView.svelte";
  import WorkbenchView from "$lib/cube-designer/WorkbenchView.svelte";
  import {
    setCubeDesignerBackend,
    setCubeDesignerAI,
  } from "$lib/cube-designer/backend";
  import {
    ossCubeDesignerBackend,
    ossCubeDesignerAI,
    fetchDatasourceSchema,
  } from "$lib/cube-designer/oss-backend";
  import { hydrateFromMondrianXml } from "./edit-load";
  import { exportToMondrianXml } from "$lib/cube-designer/mondrian-export";
  import { parseProfileTables } from "$lib/cube-designer/profile-types";
  import type { SourceTableCandidate } from "$lib/cube-designer/types";
  import { adminSchemas } from "$lib/api/admin";
  import { platform } from "$lib/stores/platform.svelte";
  import { trackDemo } from "$lib/analytics/demoAnalytics";
  import { Button } from "$lib/components/ui";
  import { FeedbackBanner } from "$lib/design-system";
  import {
    LayoutGrid,
    ArrowLeftRight,
    Sigma,
    CheckCircle2,
  } from "@lucide/svelte";
  import type { PageData } from "./$types";

  let { data }: { data: PageData } = $props();
  const dataSourceId = data.dataSourceId;

  // Store keyed on the datasource; provide the OSS backend to the designer subtree.
  const store = new SchemaCanvasStore(dataSourceId);
  setCubeDesignerBackend(ossCubeDesignerBackend);
  setCubeDesignerAI(ossCubeDesignerAI);

  let sourceError = $state<string | null>(null);
  let saving = $state(false);
  let saveMsg = $state<{ tone: "success" | "error"; text: string } | null>(null);

  // saiku#1636: on a public demo, don't let visitors persist a (possibly broken)
  // schema that would take cubes down for everyone. Save is disabled in demo mode.
  const demoMode = $derived(platform.capabilities?.demoMode === true);

  const MODES = [
    { m: "canvas", label: "Schema Canvas", Icon: LayoutGrid },
    { m: "workbench", label: "Dimensions & Hierarchies", Icon: ArrowLeftRight },
    { m: "facts", label: "Facts & Measures", Icon: Sigma },
    { m: "validate", label: "Confirm cube", Icon: CheckCircle2 },
  ] as const;

  onMount(async () => {
    if (!platform.capabilities) await platform.loadCapabilities();
    trackDemo("cube-designer", "open");
    store.switchConnection(dataSourceId);
    store.sourceLoading = true;
    store.sourceError = null;
    try {
      const r = await ossCubeDesignerBackend.profileConnection(dataSourceId);
      if (!r.ok) {
        throw new Error(`could not profile the datasource (HTTP ${r.status})`);
      }
      const profiled = parseProfileTables(await r.text());
      const onCanvas = store.tableIdentitiesOnCanvas;
      store.sourceTables = profiled.map(
        (t): SourceTableCandidate => ({
          schema: t.schema,
          name: t.name,
          columns: t.columns,
          onCanvas: onCanvas.has(t.schema ? `${t.schema}.${t.name}` : t.name),
        }),
      );
    } catch (e) {
      sourceError = e instanceof Error ? e.message : "could not profile the datasource";
    } finally {
      store.sourceLoading = false;
    }
    // saiku#1634 edit mode: if this datasource already has a Mondrian schema,
    // hydrate the canvas from it. Runs after profiling so the importer can
    // enrich imported tables against the live source catalog.
    await loadExistingSchema();
  });

  /**
   * Fetch the datasource's attached Mondrian schema (if any) and load it onto
   * the canvas. A 404 (no schema attached) is the new-cube path — leave the
   * canvas blank. A parse/read error surfaces in the source-error banner.
   */
  async function loadExistingSchema() {
    try {
      const r = await fetchDatasourceSchema(dataSourceId);
      if (!r.ok) return; // 404 ⇒ no schema attached: start a new cube, blank canvas
      const body = (await r.json()) as { mondrianXml?: string; label?: string };
      const xml = body.mondrianXml ?? "";
      if (!xml) return;
      hydrateFromMondrianXml(store, xml, dataSourceId);
      // Mark the canvas as editing a saved schema so the workbench shows saved
      // (not draft) state; Save then writes back under this name.
      store.doc.lineageId = body.label?.trim() || dataSourceId;
    } catch (e) {
      sourceError =
        e instanceof Error ? e.message : "could not load the existing schema";
    }
  }

  async function save() {
    if (demoMode) return; // saving disabled in demo mode
    saving = true;
    saveMsg = null;
    let xml: string;
    try {
      xml = exportToMondrianXml(store.doc);
    } catch (e) {
      saveMsg = {
        tone: "error",
        text: e instanceof Error ? e.message : "The cube is not ready to save yet.",
      };
      saving = false;
      return;
    }
    const name = store.doc.label?.trim() || `${dataSourceId}-cube`;
    try {
      await adminSchemas.upload(name, xml);
      store.doc.lineageId = name; // mark saved for the workbench's saved/dirty state
      saveMsg = { tone: "success", text: `Saved schema "${name}".` };
    } catch (e) {
      saveMsg = { tone: "error", text: e instanceof Error ? e.message : "Save failed." };
    } finally {
      saving = false;
    }
  }
</script>

<!-- flex-1 + min-w/h-0 so the designer fills the layout's <main> (a flex row)
     across the full width AND height, instead of h-svh (which sized to the
     viewport and left a right-hand gap / overran the topbar). -->
<div class="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
  <header class="flex shrink-0 flex-wrap items-center gap-2 border-b border-border px-3 py-2">
    <h1 class="mr-2 text-sm font-semibold text-foreground">
      Cube Designer <span class="text-muted-foreground">· {dataSourceId}</span>
    </h1>
    <!-- Segmented mode tabs — one outer border, siblings share edges; active
         tab is an inset red ring + glow (matches saiku-cloud's CanvasHeader). -->
    <nav
      class="flex items-stretch overflow-hidden rounded border border-border bg-background"
      role="tablist"
      aria-label="Designer mode"
    >
      {#each MODES as { m, label, Icon } (m)}
        {@const active = store.mode === m}
        <button
          type="button"
          role="tab"
          aria-selected={active}
          aria-label={label}
          title={label}
          class="relative inline-flex h-9 items-center justify-center gap-1.5 border-l border-border px-3 text-xs font-medium transition-colors first:border-l-0 {active
            ? 'z-10 bg-card text-primary shadow-[0_0_12px_hsl(var(--primary)/0.35)] ring-2 ring-primary/60 ring-inset'
            : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground'}"
          onclick={() => store.setMode(m)}
        >
          <Icon class="h-3.5 w-3.5" aria-hidden="true" />
          <span>{label}</span>
        </button>
      {/each}
    </nav>
    <div class="ml-auto flex items-center gap-2">
      <Button
        size="sm"
        onclick={save}
        disabled={saving || demoMode}
        title={demoMode ? "Saving is disabled in demo mode" : undefined}
      >
        {saving ? "Saving…" : "Save"}
      </Button>
      <Button
        size="sm"
        variant="ghost"
        onclick={() => goto("/admin?tab=datasources")}
      >
        Back
      </Button>
    </div>
  </header>

  {#if saveMsg}
    <div class="shrink-0 px-3 pt-2">
      <FeedbackBanner tone={saveMsg.tone} size="sm">{saveMsg.text}</FeedbackBanner>
    </div>
  {/if}
  {#if sourceError}
    <div class="shrink-0 px-3 pt-2">
      <FeedbackBanner tone="error" size="sm">{sourceError}</FeedbackBanner>
    </div>
  {/if}

  <div class="flex min-h-0 flex-1 flex-col overflow-hidden">
    {#if store.mode === "canvas"}
      <SchemaCanvasView {store} />
    {:else}
      <WorkbenchView {store} isSchemaSaved={!!store.doc.lineageId} />
    {/if}
  </div>
</div>
