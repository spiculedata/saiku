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
  import { setCubeDesignerBackend } from "$lib/cube-designer/backend";
  import { ossCubeDesignerBackend } from "$lib/cube-designer/oss-backend";
  import { exportToMondrianXml } from "$lib/cube-designer/mondrian-export";
  import { parseProfileTables } from "$lib/cube-designer/profile-types";
  import type { SourceTableCandidate } from "$lib/cube-designer/types";
  import { adminSchemas } from "$lib/api/admin";
  import Button from "$lib/components/ui/button.svelte";
  import FeedbackBanner from "$lib/design-system/FeedbackBanner.svelte";
  import type { PageData } from "./$types";

  let { data }: { data: PageData } = $props();
  const dataSourceId = data.dataSourceId;

  // Store keyed on the datasource; provide the OSS backend to the designer subtree.
  const store = new SchemaCanvasStore(dataSourceId);
  setCubeDesignerBackend(ossCubeDesignerBackend);

  let sourceError = $state<string | null>(null);
  let saving = $state(false);
  let saveMsg = $state<{ tone: "success" | "error"; text: string } | null>(null);

  const MODES = [
    { m: "canvas", label: "Schema Canvas" },
    { m: "workbench", label: "Dimensions & Hierarchies" },
    { m: "facts", label: "Facts & Measures" },
    { m: "validate", label: "Confirm cube" },
  ] as const;

  onMount(async () => {
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
  });

  async function save() {
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

<div class="flex h-svh flex-col overflow-hidden">
  <header class="flex shrink-0 flex-wrap items-center gap-2 border-b border-border px-3 py-2">
    <h1 class="mr-2 text-sm font-semibold text-foreground">
      Cube Designer <span class="text-muted-foreground">· {dataSourceId}</span>
    </h1>
    <nav class="flex items-center gap-1" role="tablist" aria-label="Designer mode">
      {#each MODES as { m, label } (m)}
        {@const active = store.mode === m}
        <button
          type="button"
          role="tab"
          aria-selected={active}
          class="rounded px-2 py-1 text-[11px] font-medium {active
            ? 'bg-primary text-primary-foreground'
            : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground'}"
          onclick={() => store.setMode(m)}
        >
          {label}
        </button>
      {/each}
    </nav>
    <div class="ml-auto flex items-center gap-2">
      <Button size="sm" onclick={save} disabled={saving}>
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
