<script lang="ts">
  import { onMount } from "svelte";
  import { Button } from "$lib/components/ui";
  import { adminSchemas, type AdminSchema } from "$lib/api/admin";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import ConfirmModal from "$lib/modals/ConfirmModal.svelte";
  import Modal from "$lib/components/Modal.svelte";
  import Skeleton from "$lib/components/Skeleton.svelte";

  let list = $state<AdminSchema[]>([]);
  let loading = $state(true);
  let error = $state<string | null>(null);
  let uploading = $state(false);
  let uploadName = $state("");
  let uploadXml = $state("");
  let deleting = $state<AdminSchema | null>(null);

  async function refresh() {
    loading = true;
    error = null;
    try {
      list = await adminSchemas.list();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  onMount(refresh);

  async function pickFile(e: Event) {
    const input = e.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    uploadName = uploadName || file.name;
    uploadXml = await file.text();
  }

  async function doUpload() {
    if (!uploadName.trim() || !uploadXml.trim()) return;
    try {
      await adminSchemas.upload(uploadName.trim(), uploadXml);
      toasts.success("Uploaded", uploadName);
      uploading = false;
      uploadName = "";
      uploadXml = "";
      await refresh();
    } catch (e) {
      toasts.danger("Upload failed", e instanceof Error ? e.message : String(e));
    }
  }

  async function doDelete() {
    if (!deleting) return;
    try {
      await adminSchemas.remove(deleting.id ?? deleting.name);
      toasts.success("Deleted", deleting.name);
      deleting = null;
      await refresh();
    } catch (e) {
      toasts.danger("Delete failed", e instanceof Error ? e.message : String(e));
    }
  }
</script>

<div class="pane">
  <header class="pane__header">
    <h2>{i18n.t("admin.tabs.schemas")}</h2>
    <Button onclick={() => (uploading = true)}>{i18n.t("admin.uploadSchema")}</Button>
  </header>
  {#if error}<p class="callout callout--danger">{error}</p>{/if}
  {#if loading}
    <Skeleton rows={4} variant="table" />
  {:else}
    <table class="data-grid">
      <thead><tr><th>Name</th><th>Path</th><th>Type</th><th></th></tr></thead>
      <tbody>
        {#each list as s}
          <tr>
            <td>{s.name}</td>
            <td>{s.path ?? ""}</td>
            <td>{s.type ?? ""}</td>
            <td class="data-grid__actions">
              <Button variant="destructive" onclick={() => (deleting = s)}>Delete</Button>
            </td>
          </tr>
        {/each}
        {#if list.length === 0}
          <tr><td colspan="4" class="data-grid__empty">No schemas.</td></tr>
        {/if}
      </tbody>
    </table>
  {/if}
</div>

<Modal title="Upload schema" open={uploading} size="lg" onClose={() => (uploading = false)}>
  <label class="field">
    <span class="field__label">Name</span>
    <input class="field__input" bind:value={uploadName} />
  </label>
  <label class="field">
    <span class="field__label">XML file</span>
    <input type="file" accept=".xml" onchange={pickFile} />
  </label>
  <label class="field">
    <span class="field__label">XML content</span>
    <textarea class="field__input xml" bind:value={uploadXml} rows="10" spellcheck="false"></textarea>
  </label>
  {#snippet footer()}
    <Button variant="outline" onclick={() => (uploading = false)}>Cancel</Button>
    <Button onclick={doUpload}>Upload</Button>
  {/snippet}
</Modal>

<ConfirmModal
  title="Delete schema"
  message={`Delete schema "${deleting?.name ?? ""}"? Cubes bound to it will stop loading.`}
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
  .xml { font-family: var(--font-mono); font-size: var(--fs-sm); resize: vertical; }
</style>
