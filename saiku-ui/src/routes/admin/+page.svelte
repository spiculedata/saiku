<script lang="ts">
  import { session } from "$lib/stores/session.svelte";
  import UsersAdmin from "$lib/views/admin/UsersAdmin.svelte";
  import DatasourcesAdmin from "$lib/views/admin/DatasourcesAdmin.svelte";
  import SchemasAdmin from "$lib/views/admin/SchemasAdmin.svelte";
  import LogsAdmin from "$lib/views/admin/LogsAdmin.svelte";
  import LoginForm from "$lib/views/LoginForm.svelte";

  type Tab = "users" | "datasources" | "schemas" | "logs";
  let tab = $state<Tab>("users");
</script>

{#if session.loading}
  <div class="loading">Loading…</div>
{:else if !session.current}
  <LoginForm />
{:else if !session.isAdmin}
  <div class="forbidden">
    <h1>Admin only</h1>
    <p>Your account does not have the <code>ROLE_ADMIN</code> grant.</p>
  </div>
{:else}
  <div class="admin">
    <nav class="admin__tabs" role="tablist">
      <button type="button" role="tab" class:active={tab === "users"} onclick={() => (tab = "users")}>Users</button>
      <button type="button" role="tab" class:active={tab === "datasources"} onclick={() => (tab = "datasources")}>Datasources</button>
      <button type="button" role="tab" class:active={tab === "schemas"} onclick={() => (tab = "schemas")}>Schemas</button>
      <button type="button" role="tab" class:active={tab === "logs"} onclick={() => (tab = "logs")}>Logs</button>
    </nav>
    <section class="admin__body">
      {#if tab === "users"}
        <UsersAdmin />
      {:else if tab === "datasources"}
        <DatasourcesAdmin />
      {:else if tab === "schemas"}
        <SchemasAdmin />
      {:else}
        <LogsAdmin />
      {/if}
    </section>
  </div>
{/if}

<style>
  .admin {
    flex: 1;
    display: flex;
    flex-direction: column;
  }
  .admin__tabs {
    display: flex;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-5);
    background: var(--bg-muted);
    border-bottom: 1px solid var(--border);
  }
  .admin__tabs button {
    padding: var(--space-2) var(--space-3);
    background: transparent;
    border: 0;
    color: var(--fg-muted);
    font: inherit;
    cursor: pointer;
    border-bottom: 2px solid transparent;
  }
  .admin__tabs .active {
    color: var(--fg);
    border-bottom-color: var(--accent);
  }
  .admin__body {
    flex: 1;
    padding: var(--space-5);
    overflow: auto;
  }
  .forbidden { margin: auto; text-align: center; }
  .loading { margin: auto; color: var(--fg-muted); }
</style>
