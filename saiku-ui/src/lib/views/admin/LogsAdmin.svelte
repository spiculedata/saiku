<script lang="ts">
  import { adminLogs } from "$lib/api/admin";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  const LOG_NAMES = ["saiku", "saiku-audit", "mondrian_sql", "mondrian"];

  let name = $state(LOG_NAMES[0]);
  let content = $state("");
  let loading = $state(false);

  async function load() {
    loading = true;
    try {
      content = await adminLogs.fetch(name);
    } catch (e) {
      toasts.danger("Log fetch failed", e instanceof Error ? e.message : String(e));
      content = "";
    } finally {
      loading = false;
    }
  }
</script>

<div class="pane">
  <header class="pane__header">
    <h2>{i18n.t("admin.tabs.logs")}</h2>
    <div class="controls">
      <select class="field__input" bind:value={name}>
        {#each LOG_NAMES as n}
          <option value={n}>{n}</option>
        {/each}
      </select>
      <button type="button" class="btn btn--primary" onclick={load} disabled={loading}>
        {loading ? i18n.t("admin.logs.loading") : i18n.t("admin.logs.fetch")}
      </button>
    </div>
  </header>
  <pre class="log">{content || i18n.t("admin.logs.idle")}</pre>
</div>

<style>
  .pane__header { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--space-3); }
  h2 { margin: 0; }
  .controls { display: flex; gap: var(--space-2); align-items: center; }
  .controls .field__input { width: 220px; }
  .log {
    margin: 0;
    padding: var(--space-3);
    background: var(--bg-muted);
    color: var(--fg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    font-family: var(--font-mono);
    font-size: var(--fs-xs);
    white-space: pre-wrap;
    max-height: 70vh;
    overflow: auto;
  }
</style>
