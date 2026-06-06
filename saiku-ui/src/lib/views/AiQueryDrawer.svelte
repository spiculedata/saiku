<script lang="ts">
  /*
   * AI Query drawer — right-side slide-in panel that translates natural-
   * language questions into MDX via the backend NL ask bridge (PR1 of
   * #1093). Each successful turn lets the user click "Edit in canvas"
   * to load the generated MDX into the active workspace tab; the canvas
   * re-runs the query against the workspace path so all existing
   * grid / chart / drill features just work.
   *
   * Chat thread (Phase 1 + 2 — multi-turn with follow-up context):
   * prior turns are sent back as `history` so follow-ups like "now
   * break it down by region" resolve against the earlier question.
   *
   * Backend: POST /saiku/api/ai/ask (saiku-core/saiku-web/.../AiQueryResource.java
   * → AiAskService). 503 = provider not configured; surfaced as a
   * banner with the reason from the response body.
   */

  import { i18n } from "$lib/stores/i18n.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { askAi, AiAskTransportError, type AskResponse, type NlAskMessageDto } from "$lib/api/aiAsk";
  import { X, Send, Sparkles, ChevronDown, ChevronRight, Copy, Trash2 } from "lucide-svelte";

  interface Props {
    open: boolean;
    onClose: () => void;
    /** Called when the user clicks "Edit in canvas" — Workspace runs the MDX. */
    onEditInCanvas: (mdx: string) => void;
  }

  let { open, onClose, onEditInCanvas }: Props = $props();

  /** One displayed turn in the chat thread. */
  interface ChatTurn {
    id: string;
    role: "user" | "assistant" | "error";
    /** User: the question. Assistant: short summary. Error: the reason. */
    text: string;
    /** Assistant only: the generated MDX for collapsible display + edit. */
    mdx?: string;
    /** Assistant only: which model the backend used. */
    model?: string;
    /** Assistant only: VALIDATION_ERROR field + candidate suggestions. */
    field?: string;
    available?: string[];
    /** Per-turn collapse flag for the MDX <details>. */
    mdxExpanded?: boolean;
  }

  let turns = $state<ChatTurn[]>([]);
  let prompt = $state<string>("");
  let inflight = $state<boolean>(false);
  /** Set when the backend returns 503 — banner persists until cleared / new ask. */
  let notConfiguredBanner = $state<string | null>(null);
  let scrollEl = $state<HTMLElement | null>(null);
  let inputEl = $state<HTMLTextAreaElement | null>(null);

  // Autoscroll on new turn.
  $effect(() => {
    if (turns.length && scrollEl) {
      // Defer until DOM paints — scrollTop on the freshly-added node lands
      // at the bottom even if the new turn pushes past the previous height.
      queueMicrotask(() => {
        if (scrollEl) scrollEl.scrollTop = scrollEl.scrollHeight;
      });
    }
  });

  // Focus the textarea when the drawer opens.
  $effect(() => {
    if (open && inputEl) {
      queueMicrotask(() => inputEl?.focus());
    }
  });

  // Esc closes.
  $effect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        onClose();
      }
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  });

  /** Convert the live chat thread into the wire `history` shape the backend
   *  expects. Skips error turns (they aren't part of the conversation). */
  function historyForRequest(): NlAskMessageDto[] {
    const out: NlAskMessageDto[] = [];
    for (const t of turns) {
      if (t.role === "user") {
        out.push({ role: "user", content: t.text });
      } else if (t.role === "assistant") {
        // Send the model's emitted summary back as `assistant` context — the
        // model is forced to emit tool_use only, so this is purely a hint
        // about what was returned last turn.
        out.push({ role: "assistant", content: t.text });
      }
    }
    return out;
  }

  function nextId(): string {
    return `t${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  }

  async function submit(): Promise<void> {
    const question = prompt.trim();
    if (!question || inflight) return;
    const cube = selection.cube;
    if (!cube) {
      // No cube — emit a local error turn rather than going to the backend.
      turns = [
        ...turns,
        {
          id: nextId(),
          role: "error",
          text: i18n.t("workspace.aiQuery.noCube"),
        },
      ];
      return;
    }

    const history = historyForRequest();
    const userTurn: ChatTurn = { id: nextId(), role: "user", text: question };
    turns = [...turns, userTurn];
    prompt = "";
    inflight = true;
    notConfiguredBanner = null;

    let resp: AskResponse;
    try {
      resp = await askAi({
        question,
        cube: {
          connectionName: cube.connection,
          catalog: cube.catalog,
          schema: cube.schema,
          cubeName: cube.name,
        },
        history,
      });
    } catch (e) {
      const message = e instanceof AiAskTransportError ? e.message : (e as Error).message;
      turns = [
        ...turns,
        {
          id: nextId(),
          role: "error",
          text: i18n.t("workspace.aiQuery.transportError").replace("{message}", message),
        },
      ];
      inflight = false;
      return;
    }

    if (resp.degraded) {
      const reason = resp.reason ?? i18n.t("workspace.aiQuery.unknownError");
      if (reason.toLowerCase().includes("not configured")) {
        notConfiguredBanner = reason;
      }
      turns = [
        ...turns,
        {
          id: nextId(),
          role: "error",
          text: reason,
        },
      ];
      inflight = false;
      return;
    }

    const ai = resp.response;
    const mdx = resp.generatedMdx ?? ai?.metadata?.generatedMdx ?? "";
    if (ai && ai.status === "VALIDATION_ERROR") {
      turns = [
        ...turns,
        {
          id: nextId(),
          role: "assistant",
          text: i18n.t("workspace.aiQuery.validationError").replace("{error}", ai.error ?? ""),
          mdx,
          model: resp.model,
          field: ai.field,
          available: ai.available,
          mdxExpanded: false,
        },
      ];
      inflight = false;
      return;
    }

    const rowCount = ai?.totalRows ?? ai?.data?.length ?? ai?.matrix?.length ?? 0;
    const summary = i18n
      .t("workspace.aiQuery.resultSummary")
      .replace("{rows}", String(rowCount));
    turns = [
      ...turns,
      {
        id: nextId(),
        role: "assistant",
        text: summary,
        mdx,
        model: resp.model,
        mdxExpanded: false,
      },
    ];
    inflight = false;

    // Auto-render the AI's MDX in the workspace canvas behind the drawer
    // so the user sees the data immediately. The drawer stays open — each
    // follow-up question updates the canvas in place. Edit-in-canvas
    // becomes a no-op repeat for the user who explicitly clicks it.
    if (mdx) {
      onEditInCanvas(mdx);
    }
  }

  function clearConversation(): void {
    turns = [];
    notConfiguredBanner = null;
  }

  function copyMdx(mdx: string): void {
    if (typeof navigator !== "undefined" && navigator.clipboard) {
      void navigator.clipboard.writeText(mdx);
    }
  }

  function toggleMdx(turn: ChatTurn): void {
    // Mutate by index to keep reactivity — Svelte 5's $state proxies
    // surface array-element field changes directly.
    const idx = turns.findIndex((t) => t.id === turn.id);
    if (idx >= 0) turns[idx].mdxExpanded = !turns[idx].mdxExpanded;
  }

  function handleKeydown(e: KeyboardEvent): void {
    // Cmd/Ctrl + Enter submits; plain Enter inserts newline.
    if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
      e.preventDefault();
      void submit();
    }
  }

  /** Replace any "edit in canvas" placeholder behaviour with the host callback. */
  function editInCanvas(mdx: string): void {
    if (!mdx) return;
    onEditInCanvas(mdx);
  }
</script>

{#if open}
  <button
    type="button"
    class="ai-drawer__scrim"
    aria-label={i18n.t("workspace.aiQuery.close")}
    onclick={onClose}
  ></button>
{/if}

<aside
  class="ai-drawer"
  class:ai-drawer--open={open}
  aria-labelledby="ai-drawer-title"
  inert={!open}
>
  <header class="ai-drawer__header">
    <Sparkles size={18} aria-hidden="true" />
    <h2 id="ai-drawer-title" class="ai-drawer__title">{i18n.t("workspace.aiQuery.title")}</h2>
    <div class="ai-drawer__header-spacer"></div>
    {#if turns.length > 0}
      <button
        type="button"
        class="ai-drawer__icon-btn"
        title={i18n.t("workspace.aiQuery.clear")}
        aria-label={i18n.t("workspace.aiQuery.clear")}
        onclick={clearConversation}
      >
        <Trash2 size={16} />
      </button>
    {/if}
    <button
      type="button"
      class="ai-drawer__icon-btn"
      title={i18n.t("workspace.aiQuery.close")}
      aria-label={i18n.t("workspace.aiQuery.close")}
      onclick={onClose}
    >
      <X size={18} />
    </button>
  </header>

  {#if notConfiguredBanner}
    <div class="ai-drawer__banner" role="alert">
      <strong>{i18n.t("workspace.aiQuery.notConfiguredHeading")}</strong>
      <p>{notConfiguredBanner}</p>
    </div>
  {/if}

  <div class="ai-drawer__messages" bind:this={scrollEl}>
    {#if turns.length === 0}
      <div class="ai-drawer__empty">
        <p>{i18n.t("workspace.aiQuery.empty")}</p>
        {#if selection.cube}
          <p class="ai-drawer__empty-cube">
            {i18n.t("workspace.aiQuery.activeCube").replace("{cube}", selection.cube.caption ?? selection.cube.name)}
          </p>
          <div class="ai-drawer__examples">
            <div class="ai-drawer__examples-label">{i18n.t("workspace.aiQuery.tryAsking")}</div>
            {#each [i18n.t("workspace.aiQuery.example1"), i18n.t("workspace.aiQuery.example2"), i18n.t("workspace.aiQuery.example3")] as example}
              <button
                type="button"
                class="ai-drawer__example"
                onclick={() => {
                  prompt = example;
                  inputEl?.focus();
                }}
              >{example}</button>
            {/each}
          </div>
        {:else}
          <p class="ai-drawer__empty-cube ai-drawer__empty-cube--warn">
            {i18n.t("workspace.aiQuery.noCube")}
          </p>
        {/if}
      </div>
    {/if}

    {#each turns as turn (turn.id)}
      {#if turn.role === "user"}
        <div class="ai-drawer__turn ai-drawer__turn--user">
          <div class="ai-drawer__bubble ai-drawer__bubble--user">{turn.text}</div>
        </div>
      {:else if turn.role === "assistant"}
        <div class="ai-drawer__turn ai-drawer__turn--assistant">
          <div class="ai-drawer__bubble ai-drawer__bubble--assistant">
            <div class="ai-drawer__summary">{turn.text}</div>
            {#if turn.available && turn.available.length}
              <div class="ai-drawer__candidates">
                <div class="ai-drawer__candidates-label">
                  {#if turn.field}
                    {i18n.t("workspace.aiQuery.didYouMean").replace("{field}", turn.field)}
                  {:else}
                    {i18n.t("workspace.aiQuery.didYouMeanGeneric")}
                  {/if}
                </div>
                <div class="ai-drawer__candidate-chips">
                  {#each turn.available as cand}
                    <button
                      type="button"
                      class="ai-drawer__chip"
                      onclick={() => {
                        prompt = prompt.trim().length === 0 ? cand : `${prompt} ${cand}`;
                        inputEl?.focus();
                      }}
                    >{cand}</button>
                  {/each}
                </div>
              </div>
            {/if}
            {#if turn.mdx}
              <div class="ai-drawer__mdx">
                <button
                  type="button"
                  class="ai-drawer__mdx-toggle"
                  onclick={() => toggleMdx(turn)}
                  aria-expanded={turn.mdxExpanded ? "true" : "false"}
                >
                  {#if turn.mdxExpanded}
                    <ChevronDown size={14} aria-hidden="true" />
                  {:else}
                    <ChevronRight size={14} aria-hidden="true" />
                  {/if}
                  <span>{i18n.t("workspace.aiQuery.mdx")}</span>
                </button>
                {#if turn.mdxExpanded}
                  <pre class="ai-drawer__mdx-pre"><code>{turn.mdx}</code></pre>
                  <div class="ai-drawer__mdx-actions">
                    <button
                      type="button"
                      class="ai-drawer__small-btn"
                      onclick={() => copyMdx(turn.mdx ?? "")}
                      title={i18n.t("workspace.aiQuery.copyMdx")}
                    >
                      <Copy size={12} aria-hidden="true" />
                      <span>{i18n.t("workspace.aiQuery.copy")}</span>
                    </button>
                    <button
                      type="button"
                      class="ai-drawer__small-btn ai-drawer__small-btn--primary"
                      onclick={() => editInCanvas(turn.mdx ?? "")}
                    >{i18n.t("workspace.aiQuery.editInCanvas")}</button>
                  </div>
                {/if}
              </div>
            {/if}
            {#if turn.model}
              <div class="ai-drawer__model">{i18n.t("workspace.aiQuery.viaModel").replace("{model}", turn.model)}</div>
            {/if}
          </div>
        </div>
      {:else}
        <div class="ai-drawer__turn ai-drawer__turn--error">
          <div class="ai-drawer__bubble ai-drawer__bubble--error">{turn.text}</div>
        </div>
      {/if}
    {/each}

    {#if inflight}
      <div class="ai-drawer__turn ai-drawer__turn--assistant">
        <div class="ai-drawer__bubble ai-drawer__bubble--assistant ai-drawer__thinking">
          <span class="ai-drawer__dot"></span>
          <span class="ai-drawer__dot"></span>
          <span class="ai-drawer__dot"></span>
        </div>
      </div>
    {/if}
  </div>

  <footer class="ai-drawer__footer">
    <textarea
      bind:this={inputEl}
      class="ai-drawer__input"
      placeholder={i18n.t("workspace.aiQuery.placeholder")}
      bind:value={prompt}
      rows="3"
      disabled={inflight}
      onkeydown={handleKeydown}
    ></textarea>
    <button
      type="button"
      class="ai-drawer__submit"
      disabled={inflight || prompt.trim().length === 0}
      onclick={() => void submit()}
      title={i18n.t("workspace.aiQuery.send")}
      aria-label={i18n.t("workspace.aiQuery.send")}
    >
      <Send size={16} aria-hidden="true" />
      <span>{i18n.t("workspace.aiQuery.send")}</span>
    </button>
  </footer>
</aside>

<style>
  .ai-drawer__scrim {
    position: fixed;
    inset: 0;
    background: transparent;
    border: 0;
    padding: 0;
    z-index: 90;
    cursor: default;
  }

  .ai-drawer {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    width: min(380px, 92vw);
    max-width: 100vw;
    background: var(--bg);
    border-left: 1px solid var(--border);
    box-shadow: -8px 0 24px rgba(0, 0, 0, 0.08);
    display: flex;
    flex-direction: column;
    transform: translateX(100%);
    transition: transform 0.18s ease-out;
    z-index: 100;
    color: var(--fg);
  }
  /* On narrow viewports the drawer takes most of the screen so nothing
     overflows past the right edge — caught visually on demo verification
     when the original 420px drawer clipped at the right of the window. */
  @media (max-width: 900px) {
    .ai-drawer {
      width: min(360px, 100vw);
    }
  }
  .ai-drawer--open {
    transform: translateX(0);
  }

  .ai-drawer__header {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 14px;
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
    /* Pin header + footer against any squeeze when `messages` swells —
       previously a tall conversation could nudge the footer below the
       viewport on short windows. */
    flex-shrink: 0;
  }
  .ai-drawer__title {
    margin: 0;
    font-size: 0.95rem;
    font-weight: 600;
  }
  .ai-drawer__header-spacer {
    flex: 1;
  }
  .ai-drawer__icon-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background: transparent;
    border: 1px solid transparent;
    color: var(--fg-muted);
    border-radius: 6px;
    padding: 5px;
    cursor: pointer;
  }
  .ai-drawer__icon-btn:hover {
    background: var(--bg);
    border-color: var(--border);
    color: var(--fg);
  }

  .ai-drawer__banner {
    margin: 12px 14px 0;
    padding: 10px 12px;
    background: rgba(245, 158, 11, 0.12);
    border: 1px solid rgba(245, 158, 11, 0.35);
    border-radius: 6px;
    font-size: 0.85rem;
  }
  .ai-drawer__banner strong {
    display: block;
    margin-bottom: 4px;
  }
  .ai-drawer__banner p {
    margin: 0;
    color: var(--fg-muted);
  }

  .ai-drawer__messages {
    flex: 1;
    overflow-y: auto;
    padding: 14px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .ai-drawer__empty {
    color: var(--fg-muted);
    font-size: 0.9rem;
    line-height: 1.5;
  }
  .ai-drawer__empty p {
    margin: 0 0 8px;
  }
  .ai-drawer__empty-cube {
    font-size: 0.8rem;
    color: var(--fg-muted);
  }
  .ai-drawer__empty-cube--warn {
    color: #b45309;
  }
  .ai-drawer__examples {
    margin-top: 14px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .ai-drawer__examples-label {
    font-size: 0.78rem;
    color: var(--fg-muted);
    margin-bottom: 2px;
  }
  .ai-drawer__example {
    text-align: left;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 8px 10px;
    font-size: 0.85rem;
    color: var(--fg);
    cursor: pointer;
    font-family: inherit;
  }
  .ai-drawer__example:hover {
    border-color: var(--accent);
    background: var(--bg-muted);
  }

  .ai-drawer__turn {
    display: flex;
  }
  .ai-drawer__turn--user {
    justify-content: flex-end;
  }
  .ai-drawer__turn--assistant,
  .ai-drawer__turn--error {
    justify-content: flex-start;
  }

  .ai-drawer__bubble {
    padding: 9px 12px;
    border-radius: 10px;
    max-width: 90%;
    font-size: 0.9rem;
    line-height: 1.4;
    white-space: pre-wrap;
    /* `overflow-wrap: anywhere` (vs the older `word-wrap: break-word`)
       breaks within long unbroken strings like MDX identifiers / URLs
       too, so a user bubble or candidate chip can never poke past the
       drawer's right edge. */
    overflow-wrap: anywhere;
    word-break: break-word;
  }
  .ai-drawer__bubble--user {
    background: var(--accent);
    color: white;
  }
  .ai-drawer__bubble--assistant {
    background: var(--bg-muted);
    border: 1px solid var(--border);
  }
  .ai-drawer__bubble--error {
    background: rgba(239, 68, 68, 0.1);
    border: 1px solid rgba(239, 68, 68, 0.35);
    color: #991b1b;
  }
  .ai-drawer__summary {
    font-weight: 500;
  }

  .ai-drawer__candidates {
    margin-top: 8px;
  }
  .ai-drawer__candidates-label {
    font-size: 0.8rem;
    color: var(--fg-muted);
    margin-bottom: 4px;
  }
  .ai-drawer__candidate-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }
  .ai-drawer__chip {
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 999px;
    padding: 3px 9px;
    font-size: 0.78rem;
    color: var(--fg);
    cursor: pointer;
  }
  .ai-drawer__chip:hover {
    background: var(--bg-muted);
    border-color: var(--accent);
  }

  .ai-drawer__mdx {
    margin-top: 10px;
    border-top: 1px solid var(--border);
    padding-top: 8px;
  }
  .ai-drawer__mdx-toggle {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    background: transparent;
    border: 0;
    color: var(--fg-muted);
    font-size: 0.78rem;
    cursor: pointer;
    padding: 2px 4px;
  }
  .ai-drawer__mdx-toggle:hover {
    color: var(--fg);
  }
  .ai-drawer__mdx-pre {
    margin: 6px 0;
    padding: 8px;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    font-size: 0.75rem;
    overflow-x: auto;
    white-space: pre-wrap;
    word-break: break-all;
  }
  .ai-drawer__mdx-actions {
    display: flex;
    gap: 6px;
    margin-top: 6px;
  }
  .ai-drawer__small-btn {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    background: var(--bg);
    border: 1px solid var(--border);
    color: var(--fg);
    border-radius: 6px;
    padding: 4px 9px;
    font-size: 0.78rem;
    cursor: pointer;
  }
  .ai-drawer__small-btn:hover {
    background: var(--bg-muted);
  }
  .ai-drawer__small-btn--primary {
    background: var(--accent);
    border-color: var(--accent);
    color: white;
  }
  .ai-drawer__small-btn--primary:hover {
    filter: brightness(0.95);
    background: var(--accent);
  }

  .ai-drawer__model {
    margin-top: 6px;
    font-size: 0.72rem;
    color: var(--fg-muted);
    font-style: italic;
  }

  .ai-drawer__thinking {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }
  .ai-drawer__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--fg-muted);
    animation: ai-drawer-pulse 1.2s ease-in-out infinite;
  }
  .ai-drawer__dot:nth-child(2) {
    animation-delay: 0.15s;
  }
  .ai-drawer__dot:nth-child(3) {
    animation-delay: 0.3s;
  }
  @keyframes ai-drawer-pulse {
    0%,
    100% {
      opacity: 0.25;
      transform: scale(0.9);
    }
    50% {
      opacity: 1;
      transform: scale(1.05);
    }
  }

  .ai-drawer__footer {
    border-top: 1px solid var(--border);
    padding: 10px 14px;
    display: flex;
    gap: 8px;
    align-items: flex-end;
    background: var(--bg);
    flex-shrink: 0;
  }
  .ai-drawer__input {
    flex: 1;
    resize: vertical;
    /* The empty state previously gave the impression there was no input
       at all — bumped min-height so the textarea has obvious presence at
       the footer even when collapsed. */
    min-height: 72px;
    max-height: 200px;
    padding: 8px 10px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
    color: var(--fg);
    font-family: inherit;
    font-size: 0.9rem;
  }
  .ai-drawer__input:focus {
    outline: 2px solid var(--accent);
    outline-offset: -1px;
    border-color: var(--accent);
  }
  .ai-drawer__submit {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    background: var(--accent);
    border: 1px solid var(--accent);
    color: white;
    border-radius: 6px;
    padding: 0 14px;
    height: 38px;
    font-size: 0.85rem;
    cursor: pointer;
    white-space: nowrap;
  }
  .ai-drawer__submit:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
</style>
