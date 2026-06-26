<script lang="ts">
  import { onMount } from "svelte";
  import { Button } from "$lib/components/ui";
  import { users, type AdminUser } from "$lib/api/admin";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import ConfirmModal from "$lib/modals/ConfirmModal.svelte";
  import Modal from "$lib/components/Modal.svelte";
  import Skeleton from "$lib/components/Skeleton.svelte";

  let list = $state<AdminUser[]>([]);
  let loading = $state(true);
  let error = $state<string | null>(null);

  let editing = $state<AdminUser | null>(null);
  let deleting = $state<AdminUser | null>(null);

  async function refresh() {
    loading = true;
    error = null;
    try {
      list = await users.list();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  onMount(refresh);

  function startNew() {
    editing = { id: 0, username: "", email: "", password: "", roles: ["ROLE_USER"] };
  }

  async function save() {
    if (!editing) return;
    try {
      if (editing.id === 0) await users.create(editing);
      else await users.update(editing);
      toasts.success("Saved", editing.username);
      editing = null;
      await refresh();
    } catch (e) {
      toasts.danger("Save failed", e instanceof Error ? e.message : String(e));
    }
  }

  async function doDelete() {
    if (!deleting) return;
    try {
      await users.remove(deleting.username);
      toasts.success("Deleted", deleting.username);
      deleting = null;
      await refresh();
    } catch (e) {
      toasts.danger("Delete failed", e instanceof Error ? e.message : String(e));
    }
  }

  function toggleRole(role: string) {
    if (!editing) return;
    editing.roles = editing.roles.includes(role)
      ? editing.roles.filter((r) => r !== role)
      : [...editing.roles, role];
  }
</script>

<div class="pane">
  <header class="flex justify-between items-center mb-3">
    <h2>{i18n.t("admin.tabs.users")}</h2>
    <Button onclick={startNew}>{i18n.t("admin.addUser")}</Button>
  </header>
  {#if error}<p class="callout callout--danger">{error}</p>{/if}
  {#if loading}
    <Skeleton rows={4} variant="table" />
  {:else}
    <table class="data-grid">
      <thead><tr>
        <th>{i18n.t("admin.users.username")}</th>
        <th>{i18n.t("admin.users.email")}</th>
        <th>{i18n.t("admin.users.roles")}</th>
        <th></th>
      </tr></thead>
      <tbody>
        {#each list as u}
          <tr>
            <td>{u.username}</td>
            <td>{u.email ?? ""}</td>
            <td>{u.roles.join(", ")}</td>
            <td class="data-grid__actions">
              <Button variant="outline" onclick={() => (editing = { ...u, password: "" })}>{i18n.t("admin.edit")}</Button>
              <Button variant="destructive" onclick={() => (deleting = u)}>{i18n.t("admin.delete")}</Button>
            </td>
          </tr>
        {/each}
        {#if list.length === 0}
          <tr><td colspan="4" class="data-grid__empty">{i18n.t("admin.empty")}</td></tr>
        {/if}
      </tbody>
    </table>
  {/if}
</div>

<Modal title={editing?.id ? "Edit user" : "New user"} open={editing !== null} size="md" onClose={() => (editing = null)}>
  {#if editing}
    <label class="field">
      <span class="field__label">Username</span>
      <input class="field__input" bind:value={editing.username} disabled={editing.id !== 0} />
    </label>
    <label class="field">
      <span class="field__label">Email</span>
      <input class="field__input" bind:value={editing.email} />
    </label>
    <label class="field">
      <span class="field__label">{editing.id === 0 ? "Password" : "New password (leave blank to keep current)"}</span>
      <input class="field__input" type="password" bind:value={editing.password} />
    </label>
    <fieldset class="field">
      <legend class="field__label">Roles</legend>
      {#each ["ROLE_USER", "ROLE_ADMIN"] as r}
        <label class="flex items-center gap-2 py-1 px-0">
          <input type="checkbox" checked={editing.roles.includes(r)} onchange={() => toggleRole(r)} />
          {r}
        </label>
      {/each}
    </fieldset>
  {/if}
  {#snippet footer()}
    <Button variant="outline" onclick={() => (editing = null)}>{i18n.t("modal.cancel")}</Button>
    <Button onclick={save}>{i18n.t("modal.save")}</Button>
  {/snippet}
</Modal>

<ConfirmModal
  title="Delete user"
  message={`Delete user "${deleting?.username ?? ""}"?`}
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
