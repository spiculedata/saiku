<script lang="ts">
  /*
   * Agent Spaces admin editor (saiku#1440). CRUD over the persona JSON files under
   * saiku-home/agent-spaces/ — a governed AI-ask persona bundles a system prompt, a
   * cube allowlist (a hard server-side scope), a skill allowlist, and suggested prompts.
   * Authoring them here replaces hand-editing JSON. Matches the admin panel's look
   * (design tokens, Button); no raw tone classes (ESLint token-only rule applies).
   */
  import { onMount } from "svelte";
  import { Button } from "$lib/components/ui";
  import { adminAgentSpaces, type AgentSpace, type AgentSpaceCubeRef } from "$lib/api/admin";
  import { listAiCubes, type AiCubeSummary } from "$lib/api/dashboards";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { Plus, Trash2, Save } from "@lucide/svelte";

  let spaces = $state<AgentSpace[]>([]);
  let cubes = $state<AiCubeSummary[]>([]);
  let selectedId = $state<string | null>(null);
  let isNew = $state(false);
  let saving = $state(false);

  // Working copy of the space being edited.
  let form = $state<AgentSpace>(blank());
  // suggestedPrompts + skillAllowlist edited as newline text for a friendlier textarea UX.
  let promptsText = $state("");
  let skillsText = $state("");

  function blank(): AgentSpace {
    return { id: "", name: "", description: "", systemPrompt: "", cubeAllowlist: [], skillAllowlist: [], suggestedPrompts: [] };
  }

  async function load() {
    try {
      [spaces, cubes] = await Promise.all([adminAgentSpaces.list(), listAiCubes().catch(() => [])]);
    } catch (e) {
      toasts.danger("Agent spaces", e instanceof Error ? e.message : String(e));
    }
  }
  onMount(() => void load());

  function edit(s: AgentSpace) {
    isNew = false;
    selectedId = s.id;
    form = { ...s, cubeAllowlist: [...s.cubeAllowlist], skillAllowlist: [...s.skillAllowlist], suggestedPrompts: [...s.suggestedPrompts] };
    promptsText = form.suggestedPrompts.join("\n");
    skillsText = form.skillAllowlist.join("\n");
  }

  function newSpace() {
    isNew = true;
    selectedId = null;
    form = blank();
    promptsText = "";
    skillsText = "";
  }

  const cubeKey = (c: AgentSpaceCubeRef | AiCubeSummary) => `${c.connectionName}/${c.catalog}/${c.schema}/${c.cubeName}`;
  const allowed = (c: AiCubeSummary) => form.cubeAllowlist.some((a) => cubeKey(a) === cubeKey(c));

  function toggleCube(c: AiCubeSummary) {
    const k = cubeKey(c);
    if (form.cubeAllowlist.some((a) => cubeKey(a) === k)) {
      form.cubeAllowlist = form.cubeAllowlist.filter((a) => cubeKey(a) !== k);
    } else {
      form.cubeAllowlist = [
        ...form.cubeAllowlist,
        { connectionName: c.connectionName, catalog: c.catalog, schema: c.schema, cubeName: c.cubeName },
      ];
    }
  }

  const idOk = $derived(/^[a-z0-9][a-z0-9-]*$/.test(form.id));

  async function save() {
    if (!idOk) {
      toasts.danger("Agent spaces", "id must be kebab-case ([a-z0-9-])");
      return;
    }
    if (!form.name.trim()) {
      toasts.danger("Agent spaces", "name is required");
      return;
    }
    saving = true;
    try {
      const payload: AgentSpace = {
        ...form,
        skillAllowlist: skillsText.split("\n").map((s) => s.trim()).filter(Boolean),
        suggestedPrompts: promptsText.split("\n").map((s) => s.trim()).filter(Boolean),
      };
      await adminAgentSpaces.save(payload);
      toasts.success("Agent spaces", `Saved “${payload.name}”`);
      await load();
      isNew = false;
      selectedId = payload.id;
    } catch (e) {
      toasts.danger("Agent spaces", e instanceof Error ? e.message : String(e));
    } finally {
      saving = false;
    }
  }

  async function remove() {
    if (!selectedId) return;
    if (!confirm(`Delete agent space “${form.name}”? This removes its JSON file.`)) return;
    try {
      await adminAgentSpaces.remove(selectedId);
      toasts.success("Agent spaces", "Deleted");
      selectedId = null;
      form = blank();
      await load();
    } catch (e) {
      toasts.danger("Agent spaces", e instanceof Error ? e.message : String(e));
    }
  }
</script>

<div class="pane">
  <header class="flex justify-between items-center">
    <div>
      <h2>Agent spaces</h2>
      <p class="text-fg-muted text-sm" style="margin:2px 0 0">Governed AI-ask personas · prompt + hard cube allowlist</p>
    </div>
    <Button onclick={newSpace}><Plus size={14} /><span>New space</span></Button>
  </header>

  <div class="layout">
    <aside class="list">
      {#if spaces.length === 0}
        <p class="text-fg-muted text-sm" style="padding:var(--space-2)">No spaces yet. Create one, or drop JSON in <code>saiku-home/agent-spaces/</code>.</p>
      {/if}
      {#each spaces as s (s.id)}
        <button type="button" class="row" class:active={s.id === selectedId && !isNew} onclick={() => edit(s)}>
          <span class="nm">{s.name}</span>
          <span class="meta">{s.cubeAllowlist.length} cube{s.cubeAllowlist.length === 1 ? "" : "s"}</span>
        </button>
      {/each}
    </aside>

    {#if isNew || selectedId}
      <section class="editor">
        <div class="grid2">
          <label class="field">
            <span>id <small>(kebab-case, becomes the filename)</small></span>
            <input bind:value={form.id} disabled={!isNew} placeholder="foodmart-sales-analyst" class:invalid={form.id !== "" && !idOk} />
          </label>
          <label class="field">
            <span>Name</span>
            <input bind:value={form.name} placeholder="FoodMart Sales Analyst" />
          </label>
        </div>
        <label class="field">
          <span>Description</span>
          <input bind:value={form.description} placeholder="One line shown in the space picker" />
        </label>
        <label class="field">
          <span>System prompt <small>(prepended to Saiku's built-in prompt — voice, focus, refusal rules)</small></span>
          <textarea bind:value={form.systemPrompt} rows="7" placeholder="You are the … Analyst. Answer only questions about …"></textarea>
        </label>

        <div class="field">
          <span>Cube allowlist <small>(hard scope — an ask against any other cube is refused 403)</small></span>
          <div class="cubes">
            {#if cubes.length === 0}
              <p class="text-fg-muted text-sm">No cubes discovered.</p>
            {/if}
            {#each cubes as c (cubeKey(c))}
              <label class="cube">
                <input type="checkbox" checked={allowed(c)} onchange={() => toggleCube(c)} />
                <span>{c.cubeName} <small class="text-fg-muted">· {c.schema}</small></span>
              </label>
            {/each}
          </div>
        </div>

        <div class="grid2">
          <label class="field">
            <span>Skill allowlist <small>(one per line; empty = all skills)</small></span>
            <textarea bind:value={skillsText} rows="4" placeholder="weekly-foodmart-rollup"></textarea>
          </label>
          <label class="field">
            <span>Suggested prompts <small>(one per line)</small></span>
            <textarea bind:value={promptsText} rows="4" placeholder="How did sales track last week?"></textarea>
          </label>
        </div>

        <div class="actions">
          <Button onclick={save} disabled={saving || !form.name.trim() || !idOk}>
            <Save size={14} /><span>{saving ? "Saving…" : "Save"}</span>
          </Button>
          {#if !isNew}
            <Button variant="outline" onclick={remove}><Trash2 size={14} /><span>Delete</span></Button>
          {/if}
        </div>
      </section>
    {:else}
      <section class="editor empty">
        <p class="text-fg-muted text-sm">Select a space to edit, or create a new one.</p>
      </section>
    {/if}
  </div>
</div>

<style>
  .pane { display: flex; flex-direction: column; gap: var(--space-4); }
  h2 { margin: 0; }
  code { font-family: var(--font-mono, monospace); font-size: var(--fs-xs); background: hsl(var(--bg-subtle)); padding: 1px 5px; border-radius: 4px; }

  .layout { display: grid; grid-template-columns: 240px 1fr; gap: var(--space-4); align-items: start; }
  .list { display: flex; flex-direction: column; gap: 2px; border: 1px solid hsl(var(--border)); border-radius: var(--radius); padding: var(--space-2); background: hsl(var(--bg-muted)); }
  .row { display: flex; flex-direction: column; align-items: flex-start; gap: 1px; text-align: left; border: 0; background: transparent; color: hsl(var(--fg-muted)); cursor: pointer; padding: var(--space-2) var(--space-3); border-radius: var(--radius); font: inherit; }
  .row:hover { color: hsl(var(--fg)); background: hsl(var(--bg-hover)); }
  .row.active { color: hsl(var(--fg)); background: hsl(var(--bg-hover)); box-shadow: inset 2px 0 0 hsl(var(--primary)); }
  .row .nm { font-weight: var(--weight-medium); }
  .row .meta { font-size: var(--fs-xs); color: hsl(var(--fg-subtle)); }

  .editor { display: flex; flex-direction: column; gap: var(--space-3); min-width: 0; }
  .editor.empty { border: 1px dashed hsl(var(--border)); border-radius: var(--radius); padding: var(--space-6); text-align: center; }
  .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
  .field { display: flex; flex-direction: column; gap: 4px; }
  .field > span { font-size: var(--fs-sm); font-weight: var(--weight-medium); }
  .field small { font-weight: 400; color: hsl(var(--fg-muted)); }
  input, textarea { font: inherit; font-size: var(--fs-sm); padding: 7px 10px; border: 1px solid hsl(var(--border)); border-radius: var(--radius); background: hsl(var(--bg)); color: hsl(var(--fg)); width: 100%; }
  textarea { resize: vertical; }
  input:disabled { color: hsl(var(--fg-muted)); background: hsl(var(--bg-muted)); }
  input.invalid { border-color: hsl(var(--danger)); }

  .cubes { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 4px; border: 1px solid hsl(var(--border)); border-radius: var(--radius); padding: var(--space-3); max-height: 200px; overflow: auto; background: hsl(var(--bg-muted)); }
  .cube { display: flex; align-items: center; gap: 8px; font-size: var(--fs-sm); cursor: pointer; }

  .actions { display: flex; gap: var(--space-2); margin-top: var(--space-2); }
</style>
