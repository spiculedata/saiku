<script lang="ts">
  /*
   * DimSum-in-a-widget. Renders an input box + result panel that POST plain-English
   * questions to /rest/saiku/api/embed/ai/{cubeId}/ask. The cube ref is pinned by the
   * token (kind="ai"); the widget supplies the question and (in v2) an optional history.
   *
   * v1 shape: single-turn — send question, render answer + generated MDX + first-row
   * summary if the query executed cleanly. No conversational history (planned).
   */
  import { askEmbedAi, EmbedFetchError, type EmbedAskResponse } from "./api";

  interface Props {
    /** Origin of the Saiku launcher — e.g. https://demo.saiku.bi. */
    server: string;
    /** The opaque embed token (kind="ai"). Public grants aren't supported for AI yet. */
    token: string;
    /** The pinned cube ref — connection/catalog/schema/cubeName. Must match the token. */
    cubeId: string;
    /** Optional initial placeholder in the input box. */
    placeholder?: string;
  }

  let {
    server,
    token,
    cubeId,
    placeholder = "Ask a question about this cube...",
  }: Props = $props();

  let question = $state("");
  let asking = $state(false);
  let error = $state<string | null>(null);
  let result = $state<EmbedAskResponse | null>(null);

  async function onSubmit(e: Event): Promise<void> {
    e.preventDefault();
    const q = question.trim();
    if (!q) return;
    asking = true;
    error = null;
    result = null;
    try {
      result = await askEmbedAi(server, cubeId, token || undefined, q);
    } catch (err: unknown) {
      if (err instanceof EmbedFetchError) {
        error = err.status === 401 ? "This embed is unavailable." : (err.body.error ?? "Ask failed.");
      } else {
        error = "Ask failed to reach the server.";
      }
    } finally {
      asking = false;
    }
  }
</script>

<div class="ask-widget">
  <form onsubmit={onSubmit} class="row">
    <input
      type="text"
      bind:value={question}
      {placeholder}
      disabled={asking}
      aria-label="Ask a question"
    />
    <button type="submit" disabled={asking || !question.trim()}>
      {asking ? "Asking…" : "Ask"}
    </button>
  </form>

  {#if error}
    <div class="state error" role="alert">{error}</div>
  {:else if result}
    {#if result.degraded}
      <div class="state warn">
        {result.reason ?? "AI ask is not configured on this deployment."}
      </div>
    {:else}
      {#if result.answer}
        <div class="answer">{result.answer}</div>
      {/if}
      {#if result.narrative && result.narrative !== result.answer}
        <div class="narrative">{result.narrative}</div>
      {/if}
      {#if result.mdx}
        <details class="mdx">
          <summary>Generated MDX</summary>
          <pre>{result.mdx}</pre>
        </details>
      {/if}
    {/if}
  {/if}
</div>

<style>
  .ask-widget {
    font-family: system-ui, sans-serif;
    font-size: 13px;
    color: var(--saiku-embed-fg, #1f2937);
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 10px 12px;
  }
  .row {
    display: flex;
    gap: 8px;
  }
  input[type="text"] {
    flex: 1;
    padding: 6px 10px;
    font-size: 13px;
    border: 1px solid var(--saiku-embed-border, #e5e7eb);
    border-radius: 6px;
    background: var(--saiku-embed-bg, #ffffff);
    color: inherit;
  }
  input[type="text"]:focus {
    outline: 2px solid var(--saiku-embed-accent, #b3170f);
    outline-offset: 1px;
  }
  button {
    padding: 6px 14px;
    font-size: 13px;
    font-weight: 600;
    color: #ffffff;
    background: var(--saiku-embed-accent, #b3170f);
    border: none;
    border-radius: 6px;
    cursor: pointer;
  }
  button:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .answer {
    padding: 8px 10px;
    background: var(--saiku-embed-header-bg, #f9fafb);
    border-radius: 6px;
    line-height: 1.5;
    white-space: pre-wrap;
  }
  .narrative {
    padding: 4px 2px;
    color: var(--saiku-embed-muted, #6b7280);
    font-size: 12px;
    line-height: 1.4;
    white-space: pre-wrap;
  }
  .mdx summary {
    cursor: pointer;
    font-size: 12px;
    color: var(--saiku-embed-muted, #6b7280);
  }
  .mdx pre {
    margin: 6px 0 0;
    padding: 8px;
    background: var(--saiku-embed-header-bg, #f9fafb);
    border-radius: 4px;
    overflow-x: auto;
    font-family: ui-monospace, monospace;
    font-size: 11px;
    line-height: 1.4;
  }
  .state {
    padding: 8px 10px;
    border-radius: 6px;
    font-size: 12px;
    line-height: 1.4;
  }
  .state.error {
    color: var(--saiku-embed-error, #b91c1c);
    background: rgba(185, 28, 28, 0.06);
  }
  .state.warn {
    color: #a25400;
    background: rgba(162, 84, 0, 0.06);
  }
</style>
