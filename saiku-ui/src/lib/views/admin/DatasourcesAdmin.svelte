<script lang="ts">
  import { onMount } from "svelte";
  import { adminDatasources, type AdminDatasource } from "$lib/api/admin";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import ConfirmModal from "$lib/modals/ConfirmModal.svelte";
  import Modal from "$lib/components/Modal.svelte";
  import Skeleton from "$lib/components/Skeleton.svelte";
  import { generateSchemaHref, generateSchemaLabel } from "./dataSourceActions";

  let list = $state<AdminDatasource[]>([]);
  let loading = $state(true);
  let error = $state<string | null>(null);
  let editing = $state<AdminDatasource | null>(null);
  let deleting = $state<AdminDatasource | null>(null);

  async function refresh() {
    loading = true;
    error = null;
    try {
      list = await adminDatasources.list();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  onMount(refresh);

  function startNew() {
    editing = {
      id: "",
      name: "",
      connectionName: "",
      driver: "mondrian.olap4j.MondrianOlap4jDriver",
      location: "",
      type: "OLAP",
      username: "",
      password: "",
      schemaName: "",
    };
  }

  async function save() {
    if (!editing) return;
    try {
      if (!editing.id) await adminDatasources.create(editing);
      else await adminDatasources.update(editing);
      toasts.success("Saved", editing.name);
      editing = null;
      await refresh();
    } catch (e) {
      toasts.danger("Save failed", e instanceof Error ? e.message : String(e));
    }
  }

  async function doDelete() {
    if (!deleting) return;
    try {
      await adminDatasources.remove(deleting.id);
      toasts.success("Deleted", deleting.name);
      deleting = null;
      await refresh();
    } catch (e) {
      toasts.danger("Delete failed", e instanceof Error ? e.message : String(e));
    }
  }

  async function refreshDs(ds: AdminDatasource) {
    try {
      await adminDatasources.refresh(ds.id);
      toasts.success("Refreshed", ds.name);
      await refresh();
    } catch (e) {
      toasts.danger("Refresh failed", e instanceof Error ? e.message : String(e));
    }
  }
</script>

<div class="pane">
  <header class="pane__header">
    <h2>{i18n.t("admin.tabs.datasources")}</h2>
    <button type="button" class="btn btn--primary" onclick={startNew}>{i18n.t("admin.addDatasource")}</button>
  </header>
  {#if error}<p class="callout callout--danger">{error}</p>{/if}
  {#if loading}
    <Skeleton rows={5} variant="table" />
  {:else}
    <table class="data-grid">
      <thead><tr><th>Name</th><th>Driver</th><th>Type</th><th>Schema</th><th></th></tr></thead>
      <tbody>
        {#each list as ds}
          <tr>
            <td>{ds.name}</td>
            <td>{ds.driver}</td>
            <td>{ds.type}</td>
            <td>{ds.schemaName ?? ""}</td>
            <td class="data-grid__actions">
              <a
                class="btn"
                data-testid="generate-schema-link"
                href={generateSchemaHref(ds)}
              >
                {generateSchemaLabel(ds)}
              </a>
              <button class="btn" onclick={() => refreshDs(ds)}>{i18n.t("admin.refresh")}</button>
              <button class="btn" onclick={() => (editing = { ...ds })}>{i18n.t("admin.edit")}</button>
              <button class="btn btn--danger" onclick={() => (deleting = ds)}>{i18n.t("admin.delete")}</button>
            </td>
          </tr>
        {/each}
        {#if list.length === 0}
          <tr><td colspan="5" class="data-grid__empty">No datasources.</td></tr>
        {/if}
      </tbody>
    </table>
  {/if}
</div>

<Modal
  title={editing?.id ? `Edit ${editing.name}` : "New datasource"}
  open={editing !== null}
  size="lg"
  onClose={() => (editing = null)}
>
  {#if editing}
    <label class="field">
      <span class="field__label">Name</span>
      <input class="field__input" bind:value={editing.name} />
    </label>
    <label class="field">
      <span class="field__label">Driver</span>
      <input class="field__input" bind:value={editing.driver} />
    </label>
    <label class="field">
      <span class="field__label">Location (JDBC url)</span>
      <input class="field__input" bind:value={editing.location} />
    </label>
    <label class="field">
      <span class="field__label">Type</span>
      <select class="field__input" bind:value={editing.type}>
        <option value="OLAP">Semantic Layer (Mondrian)</option>
        <option value="RELATIONAL">Relational</option>
      </select>
    </label>
    <label class="field">
      <span class="field__label">Schema name</span>
      <input class="field__input" bind:value={editing.schemaName} />
    </label>
    <div class="row">
      <label class="field field--grow">
        <span class="field__label">Username</span>
        <input class="field__input" bind:value={editing.username} />
      </label>
      <label class="field field--grow">
        <span class="field__label">Password</span>
        <input class="field__input" type="password" bind:value={editing.password} />
      </label>
    </div>
  {/if}
  {#snippet footer()}
    <button class="btn" onclick={() => (editing = null)}>Cancel</button>
    <button class="btn btn--primary" onclick={save}>Save</button>
  {/snippet}
</Modal>

<ConfirmModal
  title="Delete datasource"
  message={`Delete datasource "${deleting?.name ?? ""}"? Saved queries pointing at it will fail.`}
  confirmLabel="Delete"
  variant="danger"
  open={deleting !== null}
  onConfirm={doDelete}
  onCancel={() => (deleting = null)}
/>

<style>
  .pane__header { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--space-3); }
  h2 { margin: 0; }
  /* .data-grid / .data-grid__actions / .data-grid__empty come from app.css */
  .row { display: flex; gap: var(--space-3); }
  .field--grow { flex: 1; }
</style>
