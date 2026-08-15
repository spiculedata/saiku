<script lang="ts">
  /*
   * The App Builder "Ask" assistant column (saiku#1441 Phase 5). Renders a
   * natural-language chat scoped to the app's cube, hitting /ai/ask via askAi().
   * Styling defaults to a dark editorial panel and is themeable through
   * --saiku-assistant-* vars + the app's scoped custom CSS.
   */
  import { tick, untrack } from "svelte";
  import { ArrowRight, Sparkles, Crosshair } from "@lucide/svelte";
  import { askAi, type NlAskMessageDto } from "$lib/api/aiAsk";
  import type { AppAssistantSlot } from "$lib/api/apps";
  import { withGreeting, type AssistantMessage } from "$lib/views/app/appAssistant";

  interface Props {
    slot: AppAssistantSlot;
    /** Fallback cube (first queryable tile's) when the slot names none. */
    fallbackCube?: { connectionName: string; catalog: string; schema: string; cubeName: string } | null;
    /** The app's name — the panel's title when the slot doesn't set one
     *  (saiku#1761: it used to default to "FoodMart", branding every
     *  unconfigured assistant after the sample dataset). */
    appName?: string;
    /** saiku#1804: cubes this app's tiles use that the assistant is NOT bound
     *  to. Empty on a single-cube app. */
    blindCubes?: Array<{ cubeName: string }>;
  }
  let { slot, fallbackCube = null, appName, blindCubes = [] }: Props = $props();

  const cube = $derived(slot.cube ?? fallbackCube ?? null);
  const title = $derived(slot.title ?? appName ?? "this app");
  const persona = $derived(slot.persona ?? "Analyst");
  const scope = $derived(slot.scope ?? "");
  const prompts = $derived(slot.suggestedPrompts ?? []);
  const skillPrompts = $derived(slot.skillPrompts ?? []);
  const HeadIcon = $derived(slot.icon === "crosshair" ? Crosshair : Sparkles);
  const footerHint = $derived(slot.footerHint ?? "");
  const poweredBy = $derived(slot.poweredBy ?? "");

  /* saiku#1804: /ai/ask takes exactly ONE cube, but tiles carry their own, so an
     app can span several. The assistant then answers confidently about part of
     the app and knows nothing about the rest — and the author's scope note
     ("scoped to the store estate") reads as though it covers all of it. Name
     what it can actually see, so a question it can't answer looks like a scope
     limit rather than the assistant being wrong. */
  const blindNames = $derived(blindCubes.map((c) => c.cubeName).filter(Boolean));
  const scopeNote = $derived(
    blindNames.length > 0 && cube
      ? `reads ${cube.cubeName} only — not ${blindNames.join(", ")}`
      : "",
  );

  type Msg = AssistantMessage;
  let messages = $state<Msg[]>([]);
  let input = $state("");
  let busy = $state(false);
  let scroller = $state<HTMLDivElement | null>(null);

  // Seed the greeting, and RE-seed when the author edits it (saiku#1760). The
  // previous guard (`if (messages.length === 0)`) made the re-seed a no-op, so
  // an inspector edit didn't show until a full reload.
  //
  // Tracks slot.greeting only: `messages` is read inside untrack because this
  // effect also WRITES it, and a tracked read of your own write is the Svelte 5
  // re-entrancy trap (effect_update_depth_exceeded — see CLAUDE.md).
  $effect(() => {
    const g = slot.greeting;
    untrack(() => {
      messages = withGreeting(messages, g);
    });
  });

  async function scrollDown(): Promise<void> {
    await tick();
    if (scroller) scroller.scrollTop = scroller.scrollHeight;
  }

  function history(): NlAskMessageDto[] {
    return messages
      .filter((m) => m.kind !== "greeting")
      .map((m) => ({ role: m.role, content: m.text }));
  }

  async function send(text: string): Promise<void> {
    const q = text.trim();
    if (!q || busy) return;
    if (!cube) {
      messages = [...messages, { role: "assistant", text: "No cube is bound to this app yet.", kind: "error" }];
      return;
    }
    messages = [...messages, { role: "user", text: q }];
    input = "";
    busy = true;
    void scrollDown();
    try {
      const res = await askAi({ question: q, cube, history: history() });
      let reply: string;
      if (res.degraded) reply = res.reason ?? "The assistant is unavailable right now.";
      else if (res.insight?.markdown) reply = res.insight.markdown;
      else if (res.viewChange?.reason) reply = res.viewChange.reason;
      else if (res.response) {
        const rows = res.response.data?.length ?? res.response.totalRows ?? 0;
        reply = `Built a query over ${title} — ${rows} row${rows === 1 ? "" : "s"} returned.`;
      } else reply = "Done.";
      messages = [...messages, { role: "assistant", text: reply, kind: "reply" }];
    } catch (e) {
      messages = [
        ...messages,
        { role: "assistant", text: e instanceof Error ? e.message : "Something went wrong.", kind: "error" },
      ];
    } finally {
      busy = false;
      void scrollDown();
    }
  }

  function onSubmit(e: SubmitEvent): void {
    e.preventDefault();
    void send(input);
  }
  function onKeydown(e: KeyboardEvent): void {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void send(input);
    }
  }
</script>

<aside class="assistant" aria-label="Assistant">
  <header class="assistant__head">
    <span class="assistant__mark" aria-hidden="true"><HeadIcon size={16} /></span>
    <span class="assistant__title">Ask <em>{title}</em></span>
  </header>
  <div class="assistant__persona">
    Persona <span class="assistant__pill">{persona}</span>{#if scope}<span class="assistant__scope"> · {scope}</span>{/if}
  </div>
  {#if scopeNote}
    <!-- saiku#1804: only on a multi-cube app; a single-cube assistant's scope
         note is already the whole truth and this would be noise. -->
    <div class="assistant__cubes" title="The assistant answers from one cube.">{scopeNote}</div>
  {/if}

  <div class="assistant__log" bind:this={scroller}>
    {#each messages as m}
      <div class="assistant__msg assistant__msg--{m.role}" class:is-error={m.kind === "error"}>
        <!-- saiku#1781: this used to be `{title} ANALYST`, which contradicted the
             configured persona shown on the line directly above ("Persona: Supply
             Chain Analyst" over a greeting bylined "FULFILMENT ANALYST"). The
             persona already defaults to "Analyst" when unset, so the old wording
             is preserved for apps that never set one. -->
        {#if m.kind === "greeting"}<div class="assistant__eyebrow">{persona.toUpperCase()}</div>{/if}
        <p>{m.text}</p>
      </div>
    {/each}
    {#if busy}<div class="assistant__msg assistant__msg--assistant assistant__typing"><span></span><span></span><span></span></div>{/if}
  </div>

  {#if prompts.length > 0 || skillPrompts.length > 0}
    <div class="assistant__try">Try asking</div>
    <div class="assistant__prompts">
      {#each prompts as p}
        <button type="button" class="assistant__chip" disabled={busy} onclick={() => void send(p)}>{p}</button>
      {/each}
      {#each skillPrompts as sp}
        <button
          type="button"
          class="assistant__chip assistant__chip--skill"
          disabled={busy}
          onclick={() => void send(sp)}
        >
          <span class="assistant__chip-cmd" aria-hidden="true">⌘</span>{sp}
        </button>
      {/each}
    </div>
  {/if}

  <form class="assistant__form" onsubmit={onSubmit}>
    <textarea
      class="assistant__input"
      rows="1"
      placeholder={`Ask ${title}…`}
      bind:value={input}
      onkeydown={onKeydown}
      disabled={busy}
    ></textarea>
    <button type="submit" class="assistant__send" aria-label="Send" disabled={busy || !input.trim()}>
      <ArrowRight size={16} />
    </button>
  </form>
  {#if footerHint || poweredBy}
    <div class="assistant__footer">
      {#if footerHint}<span class="assistant__hint">{footerHint}</span>{/if}
      {#if poweredBy}<span class="assistant__powered">{poweredBy}</span>{/if}
    </div>
  {/if}
</aside>

<style>
  .assistant {
    width: var(--saiku-assistant-w, 22rem);
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    min-height: 0;
    background: var(--saiku-assistant-bg, #1f352a);
    color: var(--saiku-assistant-fg, #dfe8e0);
    padding: 18px 18px 16px;
    box-sizing: border-box;
    gap: 12px;
  }
  .assistant__head {
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .assistant__mark {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    border-radius: 8px;
    background: var(--saiku-app-accent-2, var(--saiku-app-accent, #c85a3a));
    color: #fff;
  }
  .assistant__title {
    font-family: Georgia, "Times New Roman", serif;
    font-size: 1.15rem;
    font-weight: 700;
    color: #fff;
  }
  .assistant__title em {
    font-style: italic;
    color: var(--saiku-assistant-accent, #e6b98f);
  }
  .assistant__persona {
    font-size: 0.8rem;
    color: #9fb4a5;
  }
  .assistant__pill {
    display: inline-block;
    padding: 2px 9px;
    border: 1px solid rgba(255, 255, 255, 0.18);
    border-radius: 999px;
    color: #eaf1ea;
    font-weight: 600;
  }
  .assistant__cubes {
    padding: 0 14px 6px;
    font-size: .68rem;
    letter-spacing: .02em;
    color: var(--saiku-assistant-muted, var(--saiku-app-muted, #8a7f68));
    opacity: .85;
  }
  .assistant__scope {
    color: #86a08f;
  }
  .assistant__log {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding-right: 2px;
  }
  .assistant__eyebrow {
    font-size: 0.6rem;
    letter-spacing: 0.12em;
    color: #86a08f;
    margin-bottom: 6px;
    font-weight: 700;
  }
  .assistant__msg {
    border-radius: 12px;
    padding: 13px 15px;
    font-size: 0.9rem;
    line-height: 1.5;
    background: rgba(255, 255, 255, 0.05);
    border: 1px solid rgba(255, 255, 255, 0.06);
  }
  .assistant__msg p {
    margin: 0;
    white-space: pre-wrap;
  }
  .assistant__msg--user {
    align-self: flex-end;
    max-width: 88%;
    background: var(--saiku-app-accent, #2e5e43);
    border-color: transparent;
    color: #fff;
  }
  .assistant__msg.is-error {
    background: rgba(200, 90, 58, 0.18);
    border-color: rgba(200, 90, 58, 0.4);
  }
  .assistant__typing {
    display: inline-flex;
    gap: 5px;
    width: fit-content;
  }
  .assistant__typing span {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #7f9a89;
    animation: blink 1.2s infinite ease-in-out both;
  }
  .assistant__typing span:nth-child(2) {
    animation-delay: 0.2s;
  }
  .assistant__typing span:nth-child(3) {
    animation-delay: 0.4s;
  }
  @keyframes blink {
    0%,
    80%,
    100% {
      opacity: 0.25;
    }
    40% {
      opacity: 1;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .assistant__typing span {
      animation: none;
    }
  }
  .assistant__try {
    font-size: 0.62rem;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: #86a08f;
    font-weight: 700;
  }
  .assistant__prompts {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .assistant__chip {
    text-align: left;
    padding: 10px 13px;
    border-radius: 10px;
    border: 1px solid rgba(255, 255, 255, 0.14);
    background: rgba(255, 255, 255, 0.03);
    color: #dfe8e0;
    font-size: 0.82rem;
    cursor: pointer;
    line-height: 1.35;
  }
  .assistant__chip:hover:not(:disabled) {
    background: rgba(255, 255, 255, 0.08);
  }
  .assistant__chip:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .assistant__chip--skill {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    width: fit-content;
    font-family:
      ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
    font-size: 0.76rem;
    color: var(--saiku-assistant-accent, #e6b98f);
    border-color: rgba(230, 185, 143, 0.28);
    background: rgba(230, 185, 143, 0.06);
  }
  .assistant__chip-cmd {
    opacity: 0.85;
  }
  .assistant__footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    font-size: 0.68rem;
    color: #6f8a79;
  }
  .assistant__powered {
    margin-left: auto;
  }
  .assistant__form {
    display: flex;
    align-items: flex-end;
    gap: 8px;
    border: 1px solid rgba(255, 255, 255, 0.16);
    border-radius: 12px;
    padding: 8px 8px 8px 12px;
    background: rgba(0, 0, 0, 0.18);
  }
  .assistant__input {
    flex: 1;
    resize: none;
    border: 0;
    background: transparent;
    color: #eef4ee;
    font: inherit;
    font-size: 0.88rem;
    outline: none;
    max-height: 120px;
  }
  .assistant__input::placeholder {
    color: #7f9a89;
  }
  .assistant__send {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    flex-shrink: 0;
    border: 0;
    border-radius: 9px;
    background: var(--saiku-app-accent-2, var(--saiku-app-accent, #c85a3a));
    color: #fff;
    cursor: pointer;
  }
  .assistant__send:disabled {
    opacity: 0.45;
    cursor: default;
  }
</style>
