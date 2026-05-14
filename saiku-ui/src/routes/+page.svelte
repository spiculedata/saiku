<script lang="ts">
  import { session } from "$lib/stores/session.svelte";
  import LoginForm from "$lib/views/LoginForm.svelte";
  import Workspace from "$lib/views/Workspace.svelte";
</script>

{#if session.loading}
  <div class="loading">Loading…</div>
{:else if session.current}
  <Workspace session={session.current} />
{:else}
  <LoginForm />
{/if}

<style>
  .loading {
    margin: auto;
    color: var(--fg-muted);
  }
</style>
