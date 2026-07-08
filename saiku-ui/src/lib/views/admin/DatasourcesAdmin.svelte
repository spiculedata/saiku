<script lang="ts">
  import { onMount } from "svelte";
  import { Button, buttonVariants } from "$lib/components/ui";
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
      connectiontype: "MONDRIAN",
      username: "",
      password: "",
      schemaName: "",
      ossieYaml: "",
    };
  }

  /**
   * True when the currently-edited datasource is Ossie-typed. Drives the field-swap so the
   * form shows the YAML path + warehouse fields instead of the Mondrian driver / catalog
   * ones. Kept as a $derived so flipping the Type dropdown auto-updates the visible fields.
   */
  const isOssie = $derived(editing?.connectiontype === "OSSIE" || editing?.type === "OSSIE");

  /**
   * Keep {@link AdminDatasource.connectiontype} in sync with the Type dropdown value so the
   * server's DataSourceMapper picks the right branch: OLAP → MONDRIAN, RELATIONAL → XMLA,
   * OSSIE → OSSIE. Called from the dropdown's onchange rather than a $effect to avoid the
   * effect-write-tracking pitfall (CLAUDE.md's Svelte 5 effect-discipline note).
   */
  /**
   * Materialise the form's `editing` state from an existing datasource. The wire carries
   * {@link AdminDatasource.connectiontype} as the persisted discriminator; the form's dropdown
   * binds to `type`, so we back-fill `type` from `connectiontype` here so opening an
   * OSSIE-typed datasource for edit lands on the OSSIE branch instead of defaulting to OLAP.
   */
  function openForEdit(ds: AdminDatasource): AdminDatasource {
    const copy: AdminDatasource = { ...ds };
    if (copy.connectiontype === "OSSIE") copy.type = "OSSIE";
    else if (copy.connectiontype === "XMLA") copy.type = "RELATIONAL";
    else if (copy.connectiontype === "MONDRIAN") copy.type = "OLAP";
    return copy;
  }

  function onTypeChange() {
    if (!editing) return;
    if (editing.type === "OSSIE") {
      editing.connectiontype = "OSSIE";
      // Driver is unused for OSSIE — the backend derives everything from the Ossie YAML.
      // We clear it so the on-disk .sds doesn't carry a misleading Mondrian driver hint.
      editing.driver = "";
    } else if (editing.type === "RELATIONAL") {
      editing.connectiontype = "XMLA";
    } else {
      editing.connectiontype = "MONDRIAN";
      if (!editing.driver) editing.driver = "mondrian.olap4j.MondrianOlap4jDriver";
    }
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
  <header class="flex justify-between items-center mb-3">
    <h2>{i18n.t("admin.tabs.datasources")}</h2>
    <Button onclick={startNew}>{i18n.t("admin.addDatasource")}</Button>
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
                class={buttonVariants({ variant: "outline" })}
                data-testid="generate-schema-link"
                href={generateSchemaHref(ds)}
              >
                {generateSchemaLabel(ds)}
              </a>
              <Button variant="outline" onclick={() => refreshDs(ds)}>{i18n.t("admin.refresh")}</Button>
              <Button variant="outline" onclick={() => (editing = openForEdit(ds))}>{i18n.t("admin.edit")}</Button>
              <Button variant="destructive" onclick={() => (deleting = ds)}>{i18n.t("admin.delete")}</Button>
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
      <span class="field__label">Type</span>
      <select class="field__input" bind:value={editing.type} onchange={onTypeChange}>
        <option value="OLAP">Semantic Layer (Mondrian)</option>
        <option value="RELATIONAL">Relational</option>
        <option value="OSSIE">Ossie (SQL semantic model)</option>
      </select>
    </label>
    {#if isOssie}
      <label class="field">
        <span class="field__label">Ossie YAML path</span>
        <input
          class="field__input"
          placeholder="/saiku-home/data/ossie/pharma.ossie.yaml"
          bind:value={editing.ossieYaml}
        />
      </label>
      <label class="field">
        <span class="field__label">Warehouse JDBC URL</span>
        <input
          class="field__input"
          placeholder="jdbc:postgresql://localhost:5432/warehouse"
          bind:value={editing.location}
        />
      </label>
      <label class="field">
        <span class="field__label">Ossie model name</span>
        <input
          class="field__input"
          placeholder="Semantic model name inside the YAML (defaults to datasource name)"
          bind:value={editing.schemaName}
        />
      </label>
    {:else}
      <label class="field">
        <span class="field__label">Driver</span>
        <input class="field__input" bind:value={editing.driver} />
      </label>
      <label class="field">
        <span class="field__label">Location (JDBC url)</span>
        <input class="field__input" bind:value={editing.location} />
      </label>
      <label class="field">
        <span class="field__label">Schema name</span>
        <input class="field__input" bind:value={editing.schemaName} />
      </label>
    {/if}
    <div class="flex gap-3">
      <label class="field flex-1">
        <span class="field__label">Username</span>
        <input class="field__input" bind:value={editing.username} />
      </label>
      <label class="field flex-1">
        <span class="field__label">Password</span>
        <input class="field__input" type="password" bind:value={editing.password} />
      </label>
    </div>
  {/if}
  {#snippet footer()}
    <Button variant="outline" onclick={() => (editing = null)}>Cancel</Button>
    <Button onclick={save}>Save</Button>
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
h2 { margin: 0; }
  /* .data-grid / .data-grid__actions / .data-grid__empty come from app.css */
</style>
