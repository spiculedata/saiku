<script lang="ts">
  import { onMount } from "svelte";
  import { users, type AdminUser } from "$lib/api/admin";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import ConfirmModal from "$lib/modals/ConfirmModal.svelte";
  import Modal from "$lib/components/Modal.svelte";

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
  <header class="pane__header">
    <h2>{i18n.t("admin.tabs.users")}</h2>
    <button type="button" class="btn btn--primary" onclick={startNew}>{i18n.t("admin.addUser")}</button>
  </header>
  {#if error}<p class="callout callout--danger">{error}</p>{/if}
  {#if loading}
    <p>{i18n.t("cubes.loading")}</p>
  {:else}
    <table class="grid">
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
            <td class="row-actions">
              <button class="btn" onclick={() => (editing = { ...u, password: "" })}>{i18n.t("admin.edit")}</button>
              <button class="btn btn--danger" onclick={() => (deleting = u)}>{i18n.t("admin.delete")}</button>
            </td>
          </tr>
        {/each}
        {#if list.length === 0}
          <tr><td colspan="4" class="empty">{i18n.t("admin.empty")}</td></tr>
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
        <label class="check">
          <input type="checkbox" checked={editing.roles.includes(r)} onchange={() => toggleRole(r)} />
          {r}
        </label>
      {/each}
    </fieldset>
  {/if}
  {#snippet footer()}
    <button class="btn" onclick={() => (editing = null)}>{i18n.t("modal.cancel")}</button>
    <button class="btn btn--primary" onclick={save}>{i18n.t("modal.save")}</button>
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
  .pane__header { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--space-3); }
  h2 { margin: 0; }
  .grid { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
  .grid th, .grid td { padding: var(--space-2); border-bottom: 1px solid var(--border); text-align: left; }
  .grid th { background: var(--bg-muted); font-weight: 600; }
  .row-actions { display: flex; gap: var(--space-1); justify-content: flex-end; }
  .empty { text-align: center; color: var(--fg-muted); padding: var(--space-4); }
  .check { display: flex; align-items: center; gap: var(--space-2); padding: var(--space-1) 0; }
</style>
