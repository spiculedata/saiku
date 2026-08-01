<script lang="ts">
  /* Inspector → Navigation. Rail vs top, collapsed default, and the pinned
   * rail footer (settings gear + avatar). Writes via appDoc.updateNav. */
  import { appDoc } from "$lib/stores/appDoc.svelte";

  const nav = $derived(appDoc.current?.nav ?? { position: "rail" as const });
  const footer = $derived(nav.footer ?? {});
  const val = (e: Event) => (e.currentTarget as HTMLInputElement).value;
  const setFooter = (patch: Record<string, unknown>) =>
    appDoc.updateNav({ footer: { ...footer, ...patch } });
</script>

<div class="insp-section">
  <div class="insp-label">Placement</div>
  <div class="insp-row"><span>Menu</span>
    <div class="insp-seg">
      <button type="button" class:is-active={nav.position !== "top"} onclick={() => appDoc.updateNav({ position: "rail" })}>Rail</button>
      <button type="button" class:is-active={nav.position === "top"} onclick={() => appDoc.updateNav({ position: "top" })}>Top</button>
    </div>
  </div>
  {#if nav.position !== "top"}
    <label class="insp-row insp-toggle"><span>Start collapsed (icons only)</span>
      <input type="checkbox" checked={nav.railCollapsed ?? false}
        onchange={(e) => appDoc.updateNav({ railCollapsed: (e.currentTarget as HTMLInputElement).checked })} />
    </label>
  {/if}
</div>

{#if nav.position !== "top"}
  <div class="insp-section">
    <div class="insp-label">Rail footer</div>
    <label class="insp-row insp-toggle"><span>Settings gear</span>
      <input type="checkbox" checked={footer.settings ?? false}
        onchange={(e) => setFooter({ settings: (e.currentTarget as HTMLInputElement).checked })} />
    </label>
    <label class="insp-row"><span>Avatar initials</span>
      <input class="insp-input" placeholder="e.g. RM" maxlength="3" value={footer.avatar ?? ""}
        oninput={(e) => setFooter({ avatar: val(e).trim() === "" ? undefined : val(e) })} />
    </label>
    <p class="insp-hint">A user disc pinned at the bottom of the rail. Leave blank to hide.</p>
  </div>
{/if}
