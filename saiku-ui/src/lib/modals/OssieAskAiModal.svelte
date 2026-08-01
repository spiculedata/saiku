<script lang="ts">
  /*
   * Natural-language ask modal for Ossie models (#1400). Two-panel layout:
   *   - Left: prompt input, question history, model context (connection + model name)
   *   - Right: the executed record set from the last response (read-only preview)
   *
   * On submit, POST /ai/ossie/ask. If the response carries `queryUsed` we surface it to
   * the parent so it can drop the shelf state into the workbench — the user can then
   * tweak the query rather than starting from scratch.
   */

  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import { Sparkles, ArrowRight, Loader2 } from "lucide-svelte";
  import { askOssieAi, type OssieAskResponse, type OssieQueryModel } from "$lib/api/ossie";

  interface Props {
    open: boolean;
    connection: string;
    modelName: string;
    onApplyQuery?: (query: OssieQueryModel) => void;
    onClose: () => void;
  }

  const { open, connection, modelName, onApplyQuery, onClose }: Props = $props();

  interface Turn {
    role: "user" | "assistant";
    content: string;
    response?: OssieAskResponse;
  }

  let history = $state<Turn[]>([]);
  let question = $state("");
  let running = $state(false);
  let error = $state<string | null>(null);

  async function submit() {
    const q = question.trim();
    if (!q || running) return;
    running = true;
    error = null;
    // Turns to pass to the API — role/content only; not the local Turn shape.
    const wireHistory = history.map((t) => ({ role: t.role, content: t.content }));
    history = [...history, { role: "user", content: q }];
    question = "";
    try {
      const resp = await askOssieAi(connection, modelName, q, wireHistory);
      const assistantTurn: Turn = {
        role: "assistant",
        content: summarise(resp),
        response: resp,
      };
      history = [...history, assistantTurn];
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      error = msg;
      // Roll back the user turn so the next attempt doesn't include the failed one.
      history = history.slice(0, -1);
    } finally {
      running = false;
    }
  }

  function applyLast() {
    // Apply the most recent assistant response's queryUsed to the workbench.
    for (let i = history.length - 1; i >= 0; i--) {
      const t = history[i];
      if (t.role === "assistant" && t.response?.queryUsed) {
        onApplyQuery?.(t.response.queryUsed);
        onClose();
        return;
      }
    }
  }

  function summarise(resp: OssieAskResponse): string {
    const rowCount = resp.response?.records?.length ?? 0;
    const cols = resp.response?.columns?.map((c) => c.key).join(", ") ?? "";
    return `${rowCount} rows returned. Columns: ${cols}`;
  }

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      void submit();
    }
  }
</script>

<Modal title="Ask the Ossie AI" {open} size="lg" onClose={onClose}>
  <div class="ossie-ask">
    <p class="ossie-ask__scope">
      <strong>{modelName}</strong> on <code>{connection}</code>
    </p>

    <div class="ossie-ask__history" role="log">
      {#each history as t (t)}
        <div
          class="ossie-ask__turn"
          class:ossie-ask__turn--user={t.role === "user"}
          class:ossie-ask__turn--assistant={t.role === "assistant"}
        >
          <span class="ossie-ask__role">{t.role === "user" ? "You" : "AI"}</span>
          <span class="ossie-ask__content">{t.content}</span>
        </div>
      {/each}
      {#if history.length === 0}
        <p class="ossie-ask__empty">
          Ask a question about your data in plain English. Examples: "Revenue by region for
          Medicaid", "Top 5 brands by rx count", "Which channels had unusual sales last
          quarter?"
        </p>
      {/if}
    </div>

    {#if error}
      <p class="ossie-ask__error" role="alert">{error}</p>
    {/if}

    <form class="ossie-ask__form" onsubmit={(e) => (e.preventDefault(), submit())}>
      <textarea
        class="ossie-ask__input"
        placeholder="Type a question and press Cmd/Ctrl+Enter"
        bind:value={question}
        onkeydown={onKeyDown}
        disabled={running}
        rows="2"
      ></textarea>
      <div class="ossie-ask__buttons">
        <Button type="submit" disabled={running || !question.trim()}>
          {#if running}
            <Loader2 size={14} class="animate-spin" />
            Thinking…
          {:else}
            <Sparkles size={14} />
            Ask
          {/if}
        </Button>
        {#if history.some((t) => t.role === "assistant" && t.response?.queryUsed)}
          <Button variant="outline" onclick={applyLast}>
            <ArrowRight size={14} />
            Apply last query
          </Button>
        {/if}
        <Button variant="outline" onclick={onClose}>Close</Button>
      </div>
    </form>
  </div>
</Modal>

<style>
  .ossie-ask {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    min-width: 600px;
  }
  .ossie-ask__scope {
    margin: 0;
    color: hsl(var(--fg-muted));
    font-size: var(--fs-sm);
  }
  .ossie-ask__history {
    max-height: 320px;
    overflow-y: auto;
    padding: var(--space-2);
    background: var(--bg-subtle, hsl(var(--bg-hover)));
    border: 1px solid hsl(var(--border));
    border-radius: 4px;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }
  .ossie-ask__empty {
    color: hsl(var(--fg-muted));
    font-size: var(--fs-sm);
    text-align: center;
    padding: var(--space-4) 0;
  }
  .ossie-ask__turn {
    display: flex;
    gap: var(--space-2);
    padding: 6px 10px;
    border-radius: 4px;
    background: hsl(var(--bg));
  }
  .ossie-ask__turn--user {
    background: color-mix(in srgb, hsl(var(--primary)) 8%, hsl(var(--bg)));
  }
  .ossie-ask__role {
    font-weight: var(--weight-semibold);
    color: hsl(var(--fg-muted));
    min-width: 40px;
  }
  .ossie-ask__content {
    flex: 1;
    white-space: pre-wrap;
  }
  .ossie-ask__error {
    color: var(--danger, #d9534f);
    background: color-mix(in srgb, var(--danger, #d9534f) 8%, transparent);
    padding: 6px 10px;
    border-radius: 4px;
    font-size: var(--fs-sm);
  }
  .ossie-ask__form {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }
  .ossie-ask__input {
    padding: 8px 10px;
    border: 1px solid hsl(var(--border-strong));
    border-radius: 4px;
    background: hsl(var(--bg));
    color: hsl(var(--fg));
    font-family: inherit;
    font-size: var(--fs-sm);
    resize: vertical;
  }
  .ossie-ask__buttons {
    display: flex;
    gap: var(--space-2);
    justify-content: flex-end;
  }
</style>
