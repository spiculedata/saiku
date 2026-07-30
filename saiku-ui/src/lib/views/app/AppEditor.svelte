<script lang="ts">
  /*
   * Top-level App Builder editor / viewer.
   *
   * Route-level lifecycle wrapper around AppShell — the app analogue of
   * DashboardEditor. Loads the .saikuapp via the appDoc store on mount and
   * whenever the path changes (SvelteKit reuses the route component across
   * navigations), then renders AppShell against appDoc.current.
   *
   * Edit vs. view: opens in edit mode (editable). A toggle in the header
   * controls flips to read-only "view" mode analogous to a dashboard's
   * presentation preview — AppShell's `editable` prop drives whether page
   * add / rename affordances and in-grid editing are shown.
   */
  import { onMount, untrack } from "svelte";
  import { appDoc } from "$lib/stores/appDoc.svelte";
  import { Button } from "$lib/components/ui";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { Save, Pencil, Eye } from "lucide-svelte";
  import AppShell from "$lib/views/app/AppShell.svelte";

  interface Props {
    appPath: string;
  }

  let { appPath }: Props = $props();

  /** View mode: edit (default) shows editing affordances, view is read-only. */
  let mode = $state<"edit" | "view">("edit");
  let saving = $state<boolean>(false);

  onMount(() => {
    untrack(() => void appDoc.loadApp(appPath));
  });

  // Path can change without a remount — reload when it does. Guard against
  // re-firing on the load's own store writes by only reacting to the path.
  let lastLoaded = $state<string | null>(null);
  $effect(() => {
    const p = appPath;
    if (p && p !== untrack(() => lastLoaded)) {
      lastLoaded = p;
      void appDoc.loadApp(p);
    }
  });

  async function handleSave(): Promise<void> {
    const app = appDoc.current;
    const path = appDoc.savedPath;
    if (!app || !path) return;
    saving = true;
    try {
      await appDoc.saveApp(path, app.name);
      toasts.success("Saved", app.name);
    } catch (e: unknown) {
      toasts.danger("Save failed", e instanceof Error ? e.message : String(e));
    } finally {
      saving = false;
    }
  }

  function toggleMode(): void {
    mode = mode === "edit" ? "view" : "edit";
  }
</script>

{#if appDoc.loading}
  <div class="app-editor__state">{i18n.t("modal.open.loading")}</div>
{:else if appDoc.error}
  <div class="app-editor__state text-danger">{appDoc.error}</div>
{:else if appDoc.current}
  <AppShell app={appDoc.current} editable={mode === "edit"}>
    {#snippet controls()}
      <Button
        variant="outline"
        size="sm"
        onclick={toggleMode}
        title={mode === "edit" ? "Switch to view mode" : "Switch to edit mode"}
      >
        {#if mode === "edit"}
          <Eye size={14} /><span>View</span>
        {:else}
          <Pencil size={14} /><span>Edit</span>
        {/if}
      </Button>
      {#if mode === "edit"}
        <Button size="sm" onclick={() => void handleSave()} disabled={saving}>
          <Save size={14} /><span>{saving ? "Saving…" : "Save"}</span>
        </Button>
      {/if}
    {/snippet}
  </AppShell>
{:else}
  <div class="app-editor__state">No app loaded.</div>
{/if}

<style>
  .app-editor__state {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--fg-muted);
    font-size: var(--fs-md);
  }
</style>
