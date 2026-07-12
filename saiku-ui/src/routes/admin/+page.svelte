<script lang="ts">
  import { session } from "$lib/stores/session.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import UsersAdmin from "$lib/views/admin/UsersAdmin.svelte";
  import DatasourcesAdmin from "$lib/views/admin/DatasourcesAdmin.svelte";
  import SchemasAdmin from "$lib/views/admin/SchemasAdmin.svelte";
  import LogsAdmin from "$lib/views/admin/LogsAdmin.svelte";
  import StatsAdmin from "$lib/views/admin/StatsAdmin.svelte";
  import EvalsAdmin from "$lib/views/admin/EvalsAdmin.svelte";
  import ApiAccessAdmin from "$lib/views/admin/ApiAccessAdmin.svelte";
  import LoginForm from "$lib/views/LoginForm.svelte";

  type Tab = "users" | "datasources" | "schemas" | "logs" | "stats" | "evals" | "api";
  let tab = $state<Tab>("users");
</script>

{#if session.loading}
  <div class="m-auto text-fg-muted">{i18n.t("cubes.loading")}</div>
{:else if !session.current}
  <LoginForm />
{:else if !session.isAdmin}
  <div class="m-auto text-center">
    <h1>{i18n.t("admin.notAllowed")}</h1>
    <p>Your account does not have the <code>ROLE_ADMIN</code> grant.</p>
  </div>
{:else}
  <div class="admin">
    <div class="admin__tabs" role="tablist">
      <button type="button" role="tab" class:active={tab === "users"} onclick={() => (tab = "users")}>{i18n.t("admin.tabs.users")}</button>
      <button type="button" role="tab" class:active={tab === "datasources"} onclick={() => (tab = "datasources")}>{i18n.t("admin.tabs.datasources")}</button>
      <button type="button" role="tab" class:active={tab === "schemas"} onclick={() => (tab = "schemas")}>{i18n.t("admin.tabs.schemas")}</button>
      <button type="button" role="tab" class:active={tab === "logs"} onclick={() => (tab = "logs")}>{i18n.t("admin.tabs.logs")}</button>
      <button type="button" role="tab" class:active={tab === "stats"} onclick={() => (tab = "stats")}>{i18n.t("admin.tabs.stats")}</button>
      <button type="button" role="tab" class:active={tab === "evals"} onclick={() => (tab = "evals")}>Agent evals</button>
      <button type="button" role="tab" class:active={tab === "api"} onclick={() => (tab = "api")}>API access</button>
    </div>
    <section class="flex-1 p-6 overflow-auto">
      {#if tab === "users"}
        <UsersAdmin />
      {:else if tab === "datasources"}
        <DatasourcesAdmin />
      {:else if tab === "schemas"}
        <SchemasAdmin />
      {:else if tab === "logs"}
        <LogsAdmin />
      {:else if tab === "stats"}
        <StatsAdmin />
      {:else if tab === "evals"}
        <EvalsAdmin />
      {:else}
        <ApiAccessAdmin defaultOpen={true} />
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
</style>
