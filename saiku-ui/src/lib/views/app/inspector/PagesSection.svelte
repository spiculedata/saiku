<script lang="ts">
  /* Inspector → Pages. Per-page meta (title / heading / subheading / meta /
   * icon) + add page. The active page is expanded. Writes via appDoc. */
  import { appDoc } from "$lib/stores/appDoc.svelte";
  import { Plus, Trash2 } from "lucide-svelte";
  import { tokenHelp } from "$lib/views/app/textTokens";
  import { pageTileCount, tilesPhrase } from "$lib/views/app/pageTiles";

  const pages = $derived(appDoc.current?.pages ?? []);
  const activeId = $derived(appDoc.activePageId);

  /* saiku#1792: pages could be added but never removed — the only delete-like
   * control in the whole shell was "Remove filter", so a mis-click on "Add page"
   * (which sits directly under the page list you click to switch pages) was
   * permanent short of hand-editing the .saikuapp JSON.
   *
   * appDoc.deletePage already existed, tested, and refuses the last page; only
   * the affordance was missing.
   *
   * Two-step inline confirm rather than window.confirm: removing a page discards
   * its whole grid, so it shouldn't be one click — but a modal dialog for an
   * inspector action is heavy, and a blocking native dialog is worse. The button
   * arms itself, names what will be lost, and disarms on cancel or page switch. */
  let armedId = $state<string | null>(null);

  /** Disarm whenever the expanded page changes, so an armed confirm can never
   *  be inherited by a different page's button. */
  $effect(() => {
    if (armedId !== null && armedId !== activeId) armedId = null;
  });

  function removePage(id: string): void {
    appDoc.deletePage(id);
    armedId = null;
  }

  /** The binding chips, with {filter:…} naming THIS app's dimension — the one
   *  the context pill filters on (saiku#1761). The list used to hardcode a
   *  FoodMart dimension, so in any other app the chip copied a binding that
   *  renders literally and reads as broken. */
  const tokens = $derived(tokenHelp(appDoc.current?.header?.contextPill?.filter?.dimension));

  /** Icon keys the rail understands (see AppNavRail ICONS map). */
  const ICON_KEYS = ["home", "chart", "trend", "cube", "boxes", "users", "people", "settings", "sparkles", "table"];

  const val = (e: Event) => (e.currentTarget as HTMLInputElement).value;
  const opt = (v: string) => (v.trim() === "" ? undefined : v);

  /** Copy-to-clipboard for the binding palette. Clipboard access can be denied
   *  (permissions / insecure origin), so a failure just leaves the confirmation
   *  unset rather than surfacing an error for a convenience action. */
  let copied = $state<string | null>(null);
  async function copy(token: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(token);
      copied = token;
    } catch {
      copied = null;
    }
  }
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
            <input class="insp-input" placeholder="{'{date:weekday}'} · Week {'{week}'}" value={p.meta ?? ""} oninput={(e) => appDoc.updatePageMeta(p.id, { meta: opt(val(e)) })} /></label>
          <details class="tokens">
            <summary>Insert a live value</summary>
            <p class="insp-hint">
              Heading, subheading and meta accept bindings — click one to copy.
              Anything else is used literally.
            </p>
            <div class="token-list">
              {#each tokens as t (t.token)}
                <button type="button" class="token" title="Copy {t.token}"
                  onclick={() => copy(t.token)}>
                  <code>{t.token}</code><span>{t.describes}</span>
                </button>
              {/each}
            </div>
            {#if copied}<p class="insp-hint">Copied {copied}</p>{/if}
          </details>

          {#if pages.length <= 1}
            <p class="insp-hint">An app keeps at least one page, so this one can't be removed.</p>
          {:else if armedId === p.id}
            {@const lost = tilesPhrase(pageTileCount(p.grid))}
            <p class="insp-hint danger-hint">
              Remove “{p.title || "Untitled"}”{lost ? ` and its ${lost}` : ""}? Undo will bring it back.
            </p>
            <div class="page-danger">
              <button type="button" class="page-remove is-armed" onclick={() => removePage(p.id)}>
                <Trash2 size={13} /> Remove page
              </button>
              <button type="button" class="page-cancel" onclick={() => (armedId = null)}>Cancel</button>
            </div>
          {:else}
            <div class="page-danger">
              <button type="button" class="page-remove" onclick={() => (armedId = p.id)}>
                <Trash2 size={13} /> Remove page
              </button>
            </div>
          {/if}
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
  .tokens summary {
    font-size: 0.76rem; font-weight: 600; color: hsl(var(--fg-muted));
    cursor: pointer; padding: 0.2rem 0;
  }
  .token-list { display: flex; flex-direction: column; gap: 0.2rem; }
  .token {
    display: flex; align-items: baseline; gap: 0.5rem; width: 100%; text-align: left;
    border: 0; background: transparent; cursor: pointer; padding: 0.2rem 0.3rem;
    border-radius: 5px; color: hsl(var(--fg-muted)); font-size: 0.72rem;
  }
  .token:hover { background: hsl(var(--bg-hover)); color: hsl(var(--fg)); }
  .token code {
    font-size: 0.72rem; color: hsl(var(--fg)); background: hsl(var(--bg-subtle));
    padding: 0 0.25em; border-radius: 3px; white-space: nowrap;
  }
  /* saiku#1792 — removal sits below the fields, visually separated, and stays
     quiet until armed. Destructive tone only on the armed state so the resting
     control doesn't shout at an author who is just editing a heading. */
  .page-danger {
    display: flex; align-items: center; gap: 0.4rem;
    margin-top: 0.15rem; padding-top: 0.45rem;
    border-top: 1px solid hsl(var(--border));
  }
  .page-remove, .page-cancel {
    display: inline-flex; align-items: center; gap: 0.3rem;
    border: 1px solid hsl(var(--border)); border-radius: 6px;
    background: transparent; color: hsl(var(--fg-muted));
    padding: 0.25rem 0.5rem; font-size: 0.76rem; cursor: pointer;
  }
  .page-remove:hover { color: hsl(var(--danger)); border-color: hsl(var(--danger)); }
  .page-remove.is-armed {
    background: hsl(var(--danger)); border-color: hsl(var(--danger)); color: #fff;
  }
  .page-cancel:hover { color: hsl(var(--fg)); }
  .danger-hint { color: hsl(var(--danger)); }
</style>
