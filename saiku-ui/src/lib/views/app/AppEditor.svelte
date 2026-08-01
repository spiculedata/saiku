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
  import { page } from "$app/state";
  import { appDoc } from "$lib/stores/appDoc.svelte";
  import { Button } from "$lib/components/ui";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { Save, Pencil, Eye, Palette } from "lucide-svelte";
  import AppShell from "$lib/views/app/AppShell.svelte";
  import BrandThemePanel from "$lib/views/app/BrandThemePanel.svelte";

  interface Props {
    appPath: string;
  }

  let { appPath }: Props = $props();

  /** View mode. A saved app opens in read-only "view" (the clean published
   *  experience — no add-tile / filters / tile toolbars); the header's Edit
   *  button toggles into "edit". Authoring a brand-new app can still start in
   *  edit via the builder entry point. */
  let mode = $state<"edit" | "view">("view");
  let saving = $state<boolean>(false);

  // Kiosk: `?chrome=none` renders the app as a pure viewer — no edit/save
  // controls, view mode forced. Latched (the app's URL-state mirror rewrites the
  // query string), same as the layout's chrome-hide.
  let kiosk = $state(false);
  $effect(() => {
    if (page.url.searchParams.get("chrome") === "none") {
      kiosk = true;
      mode = "view";
    }
  });

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
    if (mode !== "edit") themeOpen = false;
  }

  /** Brand & Theme inspector open state (edit mode only). */
  let themeOpen = $state(false);
</script>

{#if appDoc.loading}
  <div class="app-editor__state">{i18n.t("modal.open.loading")}</div>
{:else if appDoc.error}
  <div class="app-editor__state text-danger">{appDoc.error}</div>
{:else if appDoc.current}
  <AppShell app={appDoc.current} editable={mode === "edit"}>
    {#snippet controls()}
      {#if !kiosk}
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
          <Button
            variant={themeOpen ? "default" : "outline"}
            size="sm"
            onclick={() => (themeOpen = !themeOpen)}
            title="Brand & Theme"
          >
            <Palette size={14} /><span>Theme</span>
          </Button>
          <Button size="sm" onclick={() => void handleSave()} disabled={saving}>
            <Save size={14} /><span>{saving ? "Saving…" : "Save"}</span>
          </Button>
        {/if}
      {/if}
    {/snippet}
  </AppShell>
  {#if themeOpen && mode === "edit"}
    <div class="app-editor__inspector">
      <BrandThemePanel onClose={() => (themeOpen = false)} />
    </div>
  {/if}
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
  /* Brand & Theme inspector — a right-edge overlay so it floats above the app
     (including the assistant column) while editing, without reflowing it. */
  .app-editor__inspector {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    z-index: 50;
    display: flex;
    box-shadow: -8px 0 28px rgba(0, 0, 0, 0.18);
  }
</style>
