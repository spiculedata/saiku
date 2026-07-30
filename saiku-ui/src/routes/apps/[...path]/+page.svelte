<script lang="ts">
  /*
   * Apps route — the App Builder analogue of the dashboards route.
   *
   * Gates on the session store the same way the dashboards route does:
   * unauthenticated visitors get LoginForm, not a broken-fetch shell. When
   * no path is present it shows the AppIndex catalogue; with a path it mounts
   * AppEditor, which loads the .saikuapp and renders AppShell.
   */

  import { session } from "$lib/stores/session.svelte";
  import LoginForm from "$lib/views/LoginForm.svelte";
  import AppEditor from "$lib/views/app/AppEditor.svelte";
  import AppIndex from "$lib/views/app/AppIndex.svelte";

  let { data } = $props();
</script>

{#if session.loading}
  <div class="m-auto text-fg-muted">Loading…</div>
{:else if session.current}
  {#if !data.appPath}
    <AppIndex />
  {:else}
    <AppEditor appPath={data.appPath} />
  {/if}
{:else}
  <LoginForm />
{/if}
