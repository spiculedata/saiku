<script lang="ts">
  /* Inspector → Assistant. Configures the in-app "Ask" panel: on/off, title,
   * persona, greeting, icon, suggested + skill prompt chips, and the composer
   * footer. Writes via appDoc.updateAssistant. */
  import { appDoc } from "$lib/stores/appDoc.svelte";
  import { Plus, Trash2 } from "lucide-svelte";

  const a = $derived(appDoc.current?.assistantSlot ?? { enabled: false });
  const prompts = $derived(a.suggestedPrompts ?? []);
  const skills = $derived(a.skillPrompts ?? []);

  const val = (e: Event) => (e.currentTarget as HTMLInputElement).value;
  const set = (patch: Record<string, unknown>) => appDoc.updateAssistant(patch);
  const opt = (v: string) => (v.trim() === "" ? undefined : v);

  function editList(list: string[], i: number, v: string): string[] {
    const next = [...list];
    next[i] = v;
    return next;
  }
</script>

<div class="insp-section">
  <label class="insp-row insp-toggle"><span>Show assistant panel</span>
    <input type="checkbox" checked={a.enabled}
      onchange={(e) => set({ enabled: (e.currentTarget as HTMLInputElement).checked })} />
  </label>
</div>

{#if a.enabled}
  <div class="insp-section">
    <div class="insp-label">Identity</div>
    <label class="insp-row"><span>Title (Ask …)</span>
      <input class="insp-input" placeholder="FoodMart" value={a.title ?? ""} oninput={(e) => set({ title: opt(val(e)) })} />
    </label>
    <label class="insp-row"><span>Persona</span>
      <input class="insp-input" placeholder="Sales Analyst" value={a.persona ?? ""} oninput={(e) => set({ persona: opt(val(e)) })} />
    </label>
    <label class="insp-row"><span>Scope note</span>
      <input class="insp-input" placeholder="scoped to your stores" value={a.scope ?? ""} oninput={(e) => set({ scope: opt(val(e)) })} />
    </label>
    <label class="insp-row"><span>Icon</span>
      <div class="insp-seg">
        <button type="button" class:is-active={(a.icon ?? "sparkles") === "sparkles"} onclick={() => set({ icon: "sparkles" })}>Sparkles</button>
        <button type="button" class:is-active={a.icon === "crosshair"} onclick={() => set({ icon: "crosshair" })}>Crosshair</button>
      </div>
    </label>
  </div>

  <div class="insp-section">
    <div class="insp-label">Greeting</div>
    <textarea class="insp-textarea" rows="4" placeholder="Opening message…" value={a.greeting ?? ""}
      oninput={(e) => set({ greeting: opt((e.currentTarget as HTMLTextAreaElement).value) })}></textarea>
  </div>

  <div class="insp-section">
    <div class="insp-label">Suggested prompts</div>
    <div class="insp-list">
      {#each prompts as p, i (i)}
        <div class="insp-list-item">
          <input class="insp-input" value={p} oninput={(e) => set({ suggestedPrompts: editList(prompts, i, val(e)) })} />
          <button type="button" class="insp-iconbtn" aria-label="Remove"
            onclick={() => set({ suggestedPrompts: prompts.filter((_, j) => j !== i) })}><Trash2 size={14} /></button>
        </div>
      {/each}
    </div>
    <button type="button" class="insp-addbtn" onclick={() => set({ suggestedPrompts: [...prompts, ""] })}>
      <Plus size={13} /> Add prompt
    </button>
  </div>

  <div class="insp-section">
    <div class="insp-label">Skill chips (⌘)</div>
    <div class="insp-list">
      {#each skills as s, i (i)}
        <div class="insp-list-item">
          <input class="insp-input" value={s} oninput={(e) => set({ skillPrompts: editList(skills, i, val(e)) })} />
          <button type="button" class="insp-iconbtn" aria-label="Remove"
            onclick={() => set({ skillPrompts: skills.filter((_, j) => j !== i) })}><Trash2 size={14} /></button>
        </div>
      {/each}
    </div>
    <button type="button" class="insp-addbtn" onclick={() => set({ skillPrompts: [...skills, ""] })}>
      <Plus size={13} /> Add skill
    </button>
  </div>

  <div class="insp-section">
    <div class="insp-label">Composer footer</div>
    <label class="insp-row"><span>Hint</span>
      <input class="insp-input" placeholder="↵ to send · ⇧↵ new line" value={a.footerHint ?? ""} oninput={(e) => set({ footerHint: opt(val(e)) })} />
    </label>
    <label class="insp-row"><span>Attribution</span>
      <input class="insp-input" placeholder="powered by Saiku" value={a.poweredBy ?? ""} oninput={(e) => set({ poweredBy: opt(val(e)) })} />
    </label>
  </div>
{/if}
