<script lang="ts">
  /**
   * Legacy parity target: js/saiku/views/WorkspaceToolbar.js + QueryToolbar.js.
   * Actions still stubbed: wire into query service in the query-execution
   * slice.
   */

  type Action =
    | "new"
    | "open"
    | "save"
    | "saveAs"
    | "run"
    | "automatic"
    | "nonEmpty"
    | "swapAxes"
    | "mdx"
    | "exportXls"
    | "exportCsv"
    | "exportPdf";

  let autorun = $state(true);
  let nonEmpty = $state(true);

  function fire(action: Action, extra?: unknown): void {
    // eslint-disable-next-line no-console
    console.log("WorkspaceToolbar action:", action, extra);
  }
</script>

<div class="toolbar" role="toolbar" aria-label="Workspace toolbar">
  <div class="toolbar__group">
    <button class="btn" title="New query" onclick={() => fire("new")}>＋ New</button>
    <button class="btn" title="Open query" onclick={() => fire("open")}>📂 Open</button>
    <button class="btn" title="Save" onclick={() => fire("save")}>💾 Save</button>
    <button class="btn" title="Save As" onclick={() => fire("saveAs")}>Save As…</button>
  </div>
  <div class="toolbar__sep"></div>
  <div class="toolbar__group">
    <button class="btn btn--primary" title="Run query" onclick={() => fire("run")}>▶ Run</button>
    <label class="toolbar__toggle">
      <input
        type="checkbox"
        bind:checked={autorun}
        onchange={() => fire("automatic", autorun)}
      />
      Autorun
    </label>
    <label class="toolbar__toggle">
      <input
        type="checkbox"
        bind:checked={nonEmpty}
        onchange={() => fire("nonEmpty", nonEmpty)}
      />
      Non-empty
    </label>
  </div>
  <div class="toolbar__sep"></div>
  <div class="toolbar__group">
    <button class="btn" title="Swap axes" onclick={() => fire("swapAxes")}>⇄ Swap</button>
    <button class="btn" title="Show MDX" onclick={() => fire("mdx")}>MDX</button>
  </div>
  <div class="toolbar__spacer"></div>
  <div class="toolbar__group">
    <button class="btn" title="Export XLS" onclick={() => fire("exportXls")}>XLS</button>
    <button class="btn" title="Export CSV" onclick={() => fire("exportCsv")}>CSV</button>
    <button class="btn" title="Export PDF" onclick={() => fire("exportPdf")}>PDF</button>
  </div>
</div>

<style>
  .toolbar {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
    flex-wrap: wrap;
  }
  .toolbar__group {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  .toolbar__sep {
    width: 1px;
    height: 20px;
    background: var(--border);
  }
  .toolbar__spacer { flex: 1; }
  .toolbar__toggle {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    padding: var(--space-1) var(--space-2);
    color: var(--fg-muted);
    font-size: var(--fs-sm);
    cursor: pointer;
  }
  .toolbar__toggle input { cursor: pointer; }
</style>
