<script lang="ts">
  import type { SaikuSession } from "$lib/api/session";
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import CubePicker from "$lib/views/CubePicker.svelte";
  import DimensionList from "$lib/views/DimensionList.svelte";
  import WorkspaceToolbar from "$lib/views/WorkspaceToolbar.svelte";
  import QueryCanvas from "$lib/views/QueryCanvas.svelte";

  interface Props {
    session: SaikuSession;
  }

  let { session }: Props = $props();
  let aboutOpen = $state(false);
</script>

<div class="workspace">
  <aside class="workspace__sidebar">
    <CubePicker username={session.username} />
    <DimensionList username={session.username} />
    <div class="workspace__sidebar-footer">
      <button type="button" class="btn" onclick={() => (aboutOpen = true)}>{i18n.t("modal.about.title")}</button>
    </div>
  </aside>
  <section class="workspace__main">
    <div class="tabset">
      <div class="tab tab--active">Unsaved query</div>
      <button type="button" class="tab tab--new" aria-label="New query">+</button>
    </div>
    <WorkspaceToolbar />
    <QueryCanvas />
  </section>
</div>

<Modal title={i18n.t("modal.about.title")} open={aboutOpen} size="sm" onClose={() => (aboutOpen = false)}>
  <p>{i18n.t("modal.about.tagline")}</p>
  <p>
    <strong>{session.username}</strong> · {session.roles.join(", ")}
  </p>
  {#snippet footer()}
    <button class="btn btn--primary" onclick={() => (aboutOpen = false)}>{i18n.t("modal.close")}</button>
  {/snippet}
</Modal>

<style>
  .workspace {
    flex: 1;
    display: grid;
    grid-template-columns: 300px 1fr;
    gap: 1px;
    background: var(--border);
  }
  .workspace__sidebar,
  .workspace__main {
    background: var(--bg);
    overflow: auto;
  }
  .workspace__sidebar {
    display: flex;
    flex-direction: column;
    padding: var(--space-4);
    gap: var(--space-3);
  }
  .workspace__sidebar-footer {
    margin-top: auto;
    padding-top: var(--space-3);
    border-top: 1px solid var(--border);
  }
  .workspace__main {
    display: flex;
    flex-direction: column;
  }
  .tabset {
    display: flex;
    align-items: stretch;
    padding: 0 var(--space-2);
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
  }
  .tab {
    padding: var(--space-3) var(--space-4);
    color: var(--fg-muted);
    border-bottom: 2px solid transparent;
    background: transparent;
    border-top: 0;
    border-left: 0;
    border-right: 0;
    font: inherit;
    cursor: pointer;
  }
  .tab--active {
    color: var(--fg);
    border-bottom-color: var(--accent);
  }
  .tab--new {
    color: var(--fg-subtle);
  }
</style>
