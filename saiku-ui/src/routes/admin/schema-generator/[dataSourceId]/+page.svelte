<script lang="ts">
  /*
   * Schema-generator admin page.
   *
   * Composes the D1–D4 building blocks:
   *   - D1 createSchemaGenClient (REST client)
   *   - D2 createSchemaGenStore  (reactive session state + polling)
   *   - D3 SchemaTree + NodeDrawer (left pane + slide-out drawer)
   *   - D4 SuggestionsFeed (right pane)
   *
   * The page layout is a header (Start / Save / Cancel + a progress pill)
   * over a two-pane main region (tree | feed). Selecting a node in the tree
   * opens the drawer on top of the feed.
   *
   * Exit route: /admin?tab=datasources — the existing admin shell is a
   * tabbed SPA, so there is no dedicated /admin/datasources route yet.
   * TODO(D6): revisit once the data-source admin gets its own route.
   */
  import { onDestroy } from "svelte";
  import { goto } from "$app/navigation";

  import {
    createSchemaGenClient,
    type SuggestionOp,
  } from "$lib/api/schemaGen";
  import { createSchemaGenStore } from "$lib/stores/schemaGen.svelte";
  import NodeDrawer from "$lib/components/schemagen/NodeDrawer.svelte";
  import SchemaTree from "$lib/components/schemagen/SchemaTree.svelte";
  import SuggestionsFeed from "$lib/components/schemagen/SuggestionsFeed.svelte";
  import { resolveDraftNode } from "$lib/components/schemagen/schemaTree.model";
  import { toasts } from "$lib/stores/toasts.svelte";

  import {
    canCancel,
    canSave,
    canStart,
    deltaBannerText,
    hasDeltaChanges,
    stageLabel,
    stagePillColor,
  } from "./pageViewModel";

  interface Props {
    data: { dataSourceId: string };
  }
  let { data }: Props = $props();

  // Single client + store instance scoped to this page. Destroyed on unmount.
  const client = createSchemaGenClient();
  const store = createSchemaGenStore(client);

  let selectedPath = $state<string | null>(null);

  // The view-model helpers treat null stage as "no session"; the store always
  // exposes a stage, but we want "Start" enabled before the first click. Key
  // off the sessionId: null session ⇒ treat stage as null.
  const effectiveStage = $derived(
    store.sessionId === null ? null : store.stage,
  );

  const startDisabled = $derived(!canStart(effectiveStage));
  const saveDisabled = $derived(!canSave(effectiveStage));
  const cancelVisible = $derived(canCancel(effectiveStage));
  const pillColor = $derived(stagePillColor(effectiveStage));
  const pillText = $derived(stageLabel(effectiveStage));

  // Delta banner — shown in re-run mode when the reconciler found upstream
  // changes since the baseline sidecar. Counts come through the store from
  // the status poll.
  const deltaCounts = $derived({
    deltaNewCount: store.deltaNewCount,
    deltaRemovedCount: store.deltaRemovedCount,
  });
  const showDeltaBanner = $derived(hasDeltaChanges(deltaCounts));

  async function handleStart() {
    await store.start(data.dataSourceId);
  }

  async function handleSave() {
    await store.save();
    // If the save raised an error the store records it; surface that as a
    // danger toast and stay on the page so the user can retry. Otherwise we
    // confirm success and navigate away.
    if (store.error !== null) {
      toasts.danger("Save failed", store.error);
      return;
    }
    toasts.success("Schema saved");
    await goto("/admin?tab=datasources");
  }

  async function handleCancel() {
    store.stop();
    await goto("/admin?tab=datasources");
  }

  function dismissError() {
    // Clear the banner by firing a rejected op the store will ignore — the
    // store doesn't currently expose a clearError, but setting error via a
    // no-op keeps state encapsulation. We accept a tiny hack: reach in via a
    // fresh Start on the same id would re-trigger the error, so instead we
    // rely on user dismissing visually by re-running. For now, hide the
    // banner with a local flag.
    errorDismissed = true;
  }
  let errorDismissed = $state(false);
  // Re-show the banner whenever a new error appears.
  $effect(() => {
    if (store.error !== null) errorDismissed = false;
  });

  /**
   * Translate drawer edits into SuggestionOps against the schema-gen store.
   *
   * The drawer emits only three fields: caption / description / aggregator.
   * Captions and descriptions both compose a RenameOp (backend combines them
   * on a single rename). Aggregator edits compose an AggregatorOp.
   *
   * We debounce lightly by ignoring no-op edits (new value === current) to
   * avoid a storm of applyOp calls while the user types.
   */
  function handleEdit(
    path: string,
    field: "caption" | "description" | "aggregator",
    value: string,
  ) {
    const resolved = resolveDraftNode(store.draft, path);
    if (resolved === null) return;
    const node = resolved.node as {
      name: string;
      caption?: string | null;
      description?: string | null;
    };

    if (field === "caption" || field === "description") {
      const oldCaption = node.caption ?? node.name;
      const newCaption = field === "caption" ? value : oldCaption;
      const description =
        field === "description" ? value : (node.description ?? null);
      if (
        newCaption === (node.caption ?? node.name) &&
        description === (node.description ?? null)
      ) {
        return;
      }
      const op: SuggestionOp = {
        op: "rename",
        targetPath: path,
        oldCaption,
        newCaption,
        description,
        confidence: 1.0,
        rationale: "user edit",
      };
      void store.applyOp(op);
      return;
    }

    // aggregator — only meaningful on measure nodes.
    if (resolved.kind !== "measure") return;
    const oldAgg = resolved.node.aggregator ?? "";
    if (value === oldAgg) return;
    const op: SuggestionOp = {
      op: "aggregator",
      targetPath: path,
      oldAggregator: oldAgg,
      newAggregator: value,
      confidence: 1.0,
      rationale: "user edit",
    };
    void store.applyOp(op);
  }

  onDestroy(() => {
    store.stop();
  });
</script>

<div class="schemagen">
  <header class="flex items-center justify-between gap-4 py-3 px-6 border-b border-border bg-bg-muted">
    <div class="schemagen__title">
      <h1>Schema Generator</h1>
      <code class="schemagen__dsid">{data.dataSourceId}</code>
    </div>

    <div class="schemagen__actions">
      <span
        class="schemagen__pill"
        data-color={pillColor}
        aria-live="polite"
        aria-label="Pipeline stage"
      >
        {pillText}
      </span>

      <button type="button" onclick={handleStart} disabled={startDisabled}>
        Start
      </button>
      <button type="button" onclick={handleSave} disabled={saveDisabled}>
        Save
      </button>
      {#if cancelVisible}
        <button type="button" class="text-fg-muted" onclick={handleCancel}>
          Cancel
        </button>
      {/if}
    </div>
  </header>

  {#if store.failureMessage}
    <div class="schemagen__failure" role="alert">
      Pipeline failed: {store.failureMessage}
    </div>
  {/if}

  {#if store.error && !errorDismissed}
    <div class="schemagen__error" role="alert">
      <span>{store.error}</span>
      <button type="button" onclick={dismissError} aria-label="Dismiss">
        ×
      </button>
    </div>
  {/if}

  {#if showDeltaBanner}
    <div class="schemagen__delta" role="status" data-testid="delta-banner">
      <span class="font-bold" aria-hidden="true">ℹ</span>
      <span>{deltaBannerText(deltaCounts)}</span>
    </div>
  {/if}

  <main class="schemagen__main">
    <aside class="schemagen__pane border-r border-border bg-bg" aria-label="Schema tree">
      {#if store.draft === null}
        <p class="p-4 text-fg-muted">
          {store.sessionId === null
            ? "Click Start to introspect this data source."
            : "Loading draft…"}
        </p>
      {:else}
        <SchemaTree draft={store.draft} bind:selectedPath />
      {/if}
    </aside>

    <section class="schemagen__pane bg-bg" aria-label="Suggestions">
      <SuggestionsFeed
        suggestions={store.suggestions}
        onAccept={(op) => {
          void store.applyOp(op);
        }}
        onReject={(op) => {
          store.rejectOp(op);
        }}
      />
    </section>

    <aside
      class="schemagen__drawer"
      class:schemagen__drawer--open={selectedPath !== null}
      aria-hidden={selectedPath === null}
    >
      {#if selectedPath !== null}
        <div class="schemagen__drawer-head">
          <button
            type="button"
            class="schemagen__drawer-close"
            onclick={() => (selectedPath = null)}
            aria-label="Close drawer"
          >
            ×
          </button>
        </div>
        <NodeDrawer
          draft={store.draft}
          selectedPath={selectedPath}
          onEdit={handleEdit}
        />
      {/if}
    </aside>
  </main>
</div>

<style>
.schemagen {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-height: 0;
    font-family: var(--font-sans);
    color: var(--fg);
    background: var(--bg);
  }
  .schemagen__title {
    display: flex;
    align-items: baseline;
    gap: var(--space-3);
  }
  .schemagen__title h1 {
    margin: 0;
    font-size: var(--fs-lg);
    font-weight: var(--weight-semibold);
  }
  .schemagen__dsid {
    font-family: var(--font-mono);
    font-size: var(--fs-xs);
    color: var(--fg-muted);
  }
  .schemagen__actions {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  .schemagen__actions button {
    font: inherit;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg);
    color: var(--fg);
    cursor: pointer;
  }
  .schemagen__actions button:hover:not(:disabled) {
    background: var(--bg-subtle);
  }
  .schemagen__actions button:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .schemagen__pill {
    font-size: var(--fs-xs);
    font-weight: var(--weight-semibold);
    padding: 3px var(--space-2);
    border-radius: 999px;
    text-transform: uppercase;
    letter-spacing: 0.03em;
    background: var(--fg-subtle);
    color: var(--accent-fg);
  }
  .schemagen__pill[data-color="muted"] {
    background: var(--border-strong);
    color: var(--fg);
  }
  .schemagen__pill[data-color="info"] {
    background: var(--accent);
    color: var(--accent-fg);
  }
  .schemagen__pill[data-color="success"] {
    background: var(--success);
    color: #fff;
  }
  .schemagen__pill[data-color="danger"] {
    background: var(--danger, #c0392b);
    color: #fff;
  }
  .schemagen__failure,
  .schemagen__error {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-5);
    border-bottom: 1px solid var(--border);
  }
  .schemagen__failure {
    background: var(--danger, #c0392b);
    color: #fff;
  }
  .schemagen__error {
    background: var(--bg-muted);
    color: var(--fg);
  }
  .schemagen__delta {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-5);
    background: var(--accent, #2b6cb0);
    color: var(--accent-fg, #fff);
    border-bottom: 1px solid var(--border);
    font-size: var(--fs-sm);
  }
  .schemagen__error button {
    background: transparent;
    border: 0;
    font: inherit;
    font-size: var(--fs-lg);
    line-height: 1;
    cursor: pointer;
    color: inherit;
  }
  .schemagen__main {
    position: relative;
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: minmax(240px, 1fr) minmax(360px, 2fr);
    gap: 0;
  }
  .schemagen__pane {
    min-height: 0;
    overflow: auto;
  }
  .schemagen__drawer {
    position: absolute;
    top: 0;
    right: 0;
    bottom: 0;
    width: min(420px, 50%);
    background: var(--bg);
    border-left: 1px solid var(--border);
    box-shadow: -8px 0 16px -12px rgb(0 0 0 / 0.25);
    transform: translateX(100%);
    transition: transform 180ms ease;
    display: flex;
    flex-direction: column;
  }
  .schemagen__drawer--open {
    transform: translateX(0);
  }
  .schemagen__drawer-head {
    display: flex;
    justify-content: flex-end;
    padding: var(--space-2) var(--space-2) 0;
  }
  .schemagen__drawer-close {
    background: transparent;
    border: 0;
    font: inherit;
    font-size: var(--fs-lg);
    line-height: 1;
    padding: var(--space-1) var(--space-2);
    cursor: pointer;
    color: var(--fg-muted);
  }
  .schemagen__drawer-close:hover {
    color: var(--fg);
  }
</style>
