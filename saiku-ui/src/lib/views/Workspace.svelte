<script lang="ts">
  import type { SaikuSession } from "$lib/api/session";
  import Modal from "$lib/components/Modal.svelte";

  interface Props {
    session: SaikuSession;
  }

  let { session }: Props = $props();
  let aboutOpen = $state(false);
</script>

<div class="workspace">
  <aside class="workspace__sidebar">
    <div class="workspace__sidebar-header">
      <h2>Cubes</h2>
      <button type="button" class="btn" onclick={() => (aboutOpen = true)}>About</button>
    </div>
    <div class="placeholder">Datasource tree — next slice</div>
  </aside>
  <section class="workspace__main">
    <div class="tabset">
      <div class="tab tab--active">Unsaved query</div>
    </div>
    <div class="workspace__content">
      <p>Welcome, <strong>{session.username}</strong>.</p>
      <p class="workspace__roles">Roles: {session.roles.join(", ")}</p>
      <div class="placeholder placeholder--grid">
        Pivot grid (AG Grid) lands in the next Phase 4 slice.
      </div>
    </div>
  </section>
</div>

<Modal title="About Saiku" open={aboutOpen} size="sm" onClose={() => (aboutOpen = false)}>
  <p>Saiku 3.17 — modernised stack.</p>
  <p>Signed in as <strong>{session.username}</strong> ({session.roles.join(", ")}).</p>
  {#snippet footer()}
    <button class="btn btn--primary" onclick={() => (aboutOpen = false)}>Close</button>
  {/snippet}
</Modal>

<style>
  .workspace {
    flex: 1;
    display: grid;
    grid-template-columns: 280px 1fr;
    gap: 1px;
    background: var(--border);
  }
  .workspace__sidebar,
  .workspace__main {
    background: var(--bg);
    overflow: auto;
  }
  .workspace__sidebar {
    padding: var(--space-4);
  }
  .workspace__sidebar-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--space-3);
  }
  .workspace__sidebar h2 {
    margin: 0;
    font-size: var(--fs-md);
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .workspace__main {
    display: flex;
    flex-direction: column;
  }
  .tabset {
    display: flex;
    padding: 0 var(--space-4);
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
  }
  .tab {
    padding: var(--space-3) var(--space-4);
    color: var(--fg-muted);
    border-bottom: 2px solid transparent;
  }
  .tab--active {
    color: var(--fg);
    border-bottom-color: var(--accent);
  }
  .workspace__content {
    padding: var(--space-5);
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
  }
  .workspace__roles {
    color: var(--fg-muted);
    font-size: var(--fs-sm);
  }
  .placeholder {
    padding: var(--space-5);
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-md);
    color: var(--fg-subtle);
    text-align: center;
  }
  .placeholder--grid {
    min-height: 360px;
  }
</style>
