<script lang="ts">
  import { session } from "$lib/stores/session.svelte";
  import LoginForm from "$lib/views/LoginForm.svelte";
  import Workspace from "$lib/views/Workspace.svelte";
  // The first-run tour highlights workspace-only affordances (cubes
  // picker, dropzones, view toggle), so mount it on this route only —
  // previously it lived in +layout.svelte and fired on every page
  // (dashboards, admin, share viewer) where its target selectors
  // didn't exist, anchoring its bubble nowhere useful.
  import Tour from "$lib/components/Tour.svelte";
</script>

{#if session.loading}
  <div class="loading">Loading…</div>
{:else if session.current}
  <Workspace session={session.current} />
  <Tour />
{:else}
  <LoginForm />
{/if}

<style>
  .loading {
    margin: auto;
    color: var(--fg-muted);
  }
</style>
