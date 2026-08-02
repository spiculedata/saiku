<script lang="ts">
  /* Inspector → Pages. Per-page meta (title / heading / subheading / meta /
   * icon) + add page. The active page is expanded. Writes via appDoc. */
  import { appDoc } from "$lib/stores/appDoc.svelte";
  import { Plus } from "lucide-svelte";

  const pages = $derived(appDoc.current?.pages ?? []);
  const activeId = $derived(appDoc.activePageId);

  /** Icon keys the rail understands (see AppNavRail ICONS map). */
  const ICON_KEYS = ["home", "chart", "trend", "cube", "boxes", "users", "people", "settings", "sparkles", "table"];

  const val = (e: Event) => (e.currentTarget as HTMLInputElement).value;
  const opt = (v: string) => (v.trim() === "" ? undefined : v);
</script>

<div class="insp-section">
  <div class="insp-label">Pages</div>
  {#each pages as p (p.id)}
    <div class="page" class:is-active={p.id === activeId}>
      <button type="button" class="page-head" onclick={() => appDoc.setActivePage(p.id)}>
        {p.title || "Untitled"}{#if p.id === activeId}<span class="dot">●</span>{/if}
      </button>
      {#if p.id === activeId}
        <div class="page-fields">
          <label class="insp-row"><span>Tab title</span>
            <input class="insp-input" value={p.title ?? ""} oninput={(e) => appDoc.updatePageMeta(p.id, { title: val(e) })} /></label>
          <label class="insp-row"><span>Icon</span>
            <select class="insp-select" value={p.icon ?? ""} onchange={(e) => appDoc.updatePageMeta(p.id, { icon: opt((e.currentTarget as HTMLSelectElement).value) })}>
              <option value="">— default —</option>
              {#each ICON_KEYS as k (k)}<option value={k}>{k}</option>{/each}
            </select></label>
          <label class="insp-row"><span>Heading</span>
            <input class="insp-input" placeholder="Portland #14 · Today" value={p.heading ?? ""} oninput={(e) => appDoc.updatePageMeta(p.id, { heading: opt(val(e)) })} /></label>
          <label class="insp-row"><span>Subheading</span>
            <input class="insp-input" placeholder="Regional manager view" value={p.subheading ?? ""} oninput={(e) => appDoc.updatePageMeta(p.id, { subheading: opt(val(e)) })} /></label>
          <label class="insp-row"><span>Meta (right)</span>
            <input class="insp-input" placeholder="Thu · Week 41" value={p.meta ?? ""} oninput={(e) => appDoc.updatePageMeta(p.id, { meta: opt(val(e)) })} /></label>
        </div>
      {/if}
    </div>
  {/each}
  <button type="button" class="insp-addbtn" onclick={() => appDoc.addPage()}><Plus size={13} /> Add page</button>
</div>

<style>
  .page { border: 1px solid hsl(var(--border)); border-radius: 8px; margin-bottom: 0.4rem; overflow: hidden; }
  .page.is-active { border-color: hsl(var(--primary)); }
  .page-head {
    width: 100%; text-align: left; border: 0; background: hsl(var(--bg-subtle));
    padding: 0.45rem 0.6rem; font-size: 0.82rem; font-weight: 600; color: hsl(var(--fg));
    cursor: pointer; display: flex; align-items: center; gap: 0.4rem;
  }
  .dot { color: hsl(var(--primary)); font-size: 0.6rem; margin-left: auto; }
  .page-fields { display: flex; flex-direction: column; gap: 0.4rem; padding: 0.5rem 0.6rem 0.7rem; }
</style>
