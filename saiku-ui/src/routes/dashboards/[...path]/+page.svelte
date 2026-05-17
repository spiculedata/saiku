<script lang="ts">
  /*
   * Dashboards route.
   *
   * Gates on the session store the same way the root workspace does —
   * unauthenticated visitors get LoginForm, not a broken-fetch tile
   * full of "NetworkError" messages. After login the session store
   * re-resolves and the editor mounts with a live cookie.
   */

  import { session } from "$lib/stores/session.svelte";
  import LoginForm from "$lib/views/LoginForm.svelte";
  import DashboardEditor from "$lib/views/dashboard/DashboardEditor.svelte";

  let { data } = $props();
</script>

{#if session.loading}
  <div class="loading">Loading…</div>
{:else if session.current}
  <DashboardEditor dashboardPath={data.dashboardPath} />
{:else}
  <LoginForm />
{/if}

<style>
  .loading {
    margin: auto;
    color: var(--fg-muted);
  }
</style>
