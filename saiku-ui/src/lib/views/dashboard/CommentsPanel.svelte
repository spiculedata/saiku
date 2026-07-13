<script lang="ts">
  /*
   * #942 — per-tile comments thread. Lists comments for one tile, lets the user
   * post (with @-mention autocomplete from the user directory), and delete their
   * own (admins delete any). Mentions are parsed + stored server-side.
   */
  import { tick } from "svelte";
  import { Button } from "$lib/components/ui";
  import Modal from "$lib/components/Modal.svelte";
  import { Trash2, Send } from "lucide-svelte";
  import { session } from "$lib/stores/session.svelte";
  import {
    getComments,
    postComment,
    deleteComment,
    getUsers,
    type DashboardComment,
  } from "$lib/api/dashboards";
  import { applyMention, currentMentionToken, matchUsers } from "$lib/dashboard/mentionAutocomplete";

  interface Props {
    dashboardPath: string;
    tileId: string;
    tileTitle?: string;
    open: boolean;
    onClose: () => void;
    /** Bubble the live comment count up so the tile badge stays in sync. */
    onCountChange?: (count: number) => void;
  }

  let { dashboardPath, tileId, tileTitle, open, onClose, onCountChange }: Props = $props();

  let comments = $state<DashboardComment[]>([]);
  let loading = $state(false);
  let error = $state<string | null>(null);
  let body = $state("");
  let posting = $state(false);

  $effect(() => {
    if (open) {
      void load();
    }
  });

  async function load(): Promise<void> {
    loading = true;
    error = null;
    try {
      comments = await getComments(dashboardPath, tileId);
      onCountChange?.(comments.length);
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to load comments";
    } finally {
      loading = false;
    }
  }

  async function post(): Promise<void> {
    const text = body.trim();
    if (!text) return;
    posting = true;
    error = null;
    try {
      await postComment(dashboardPath, tileId, text);
      body = "";
      await load();
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to post";
    } finally {
      posting = false;
    }
  }

  async function remove(id: string): Promise<void> {
    try {
      await deleteComment(dashboardPath, id);
      await load();
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to delete";
    }
  }

  function canDelete(c: DashboardComment): boolean {
    return session.current?.username === c.author || session.isAdmin;
  }

  function fmtDate(ms: number): string {
    try {
      return new Date(ms).toLocaleString();
    } catch {
      return String(ms);
    }
  }

  /* ----------------------- @-mention autocomplete ---------------------- */

  let textareaEl = $state<HTMLTextAreaElement | null>(null);
  let usernames = $state<string[]>([]);
  let usersLoaded = false;
  let suggestions = $state<string[]>([]);
  let selectedIndex = $state(0);

  async function ensureUsers(): Promise<void> {
    if (usersLoaded) return;
    usersLoaded = true;
    try {
      usernames = (await getUsers()).map((u) => u.username);
    } catch {
      usernames = [];
    }
  }

  async function refreshSuggestions(): Promise<void> {
    const caret = textareaEl?.selectionStart ?? body.length;
    const tok = currentMentionToken(body, caret);
    if (!tok) {
      suggestions = [];
      return;
    }
    await ensureUsers();
    suggestions = matchUsers(usernames, tok.query);
    selectedIndex = 0;
  }

  async function pickMention(username: string): Promise<void> {
    const caret = textareaEl?.selectionStart ?? body.length;
    const r = applyMention(body, caret, username);
    body = r.text;
    suggestions = [];
    await tick();
    if (textareaEl) {
      textareaEl.focus();
      textareaEl.setSelectionRange(r.caret, r.caret);
    }
  }

  function onKeydown(e: KeyboardEvent): void {
    if (suggestions.length > 0) {
      // Navigate / accept the mention dropdown.
      if (e.key === "ArrowDown") {
        e.preventDefault();
        selectedIndex = (selectedIndex + 1) % suggestions.length;
        return;
      }
      if (e.key === "ArrowUp") {
        e.preventDefault();
        selectedIndex = (selectedIndex - 1 + suggestions.length) % suggestions.length;
        return;
      }
      if (e.key === "Enter" || e.key === "Tab") {
        e.preventDefault();
        void pickMention(suggestions[selectedIndex]);
        return;
      }
      if (e.key === "Escape") {
        e.preventDefault();
        suggestions = [];
        return;
      }
    }
    // Ctrl/Cmd+Enter posts (when not selecting a mention).
    if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
      e.preventDefault();
      void post();
    }
  }
</script>

<Modal title={tileTitle ? `Comments — ${tileTitle}` : "Comments"} {open} size="md" {onClose}>
  <div class="cmts">
    {#if loading}
      <p class="cmts__state">Loading…</p>
    {:else if error}
      <p class="cmts__state text-danger">{error}</p>
    {:else if comments.length === 0}
      <p class="cmts__state">No comments yet. Start the conversation about this tile.</p>
    {:else}
      <ul class="cmts__list">
        {#each comments as c (c.id)}
          <li class="border border-border rounded-sm p-2 bg-bg-subtle">
            <div class="flex items-center gap-2">
              <span class="font-bold text-sm">{c.author}</span>
              <span class="cmts__date">{fmtDate(c.createdAt)}</span>
              {#if canDelete(c)}
                <button type="button" class="cmts__del" onclick={() => remove(c.id)} title="Delete comment">
                  <Trash2 size={13} />
                </button>
              {/if}
            </div>
            <p class="cmts__body">{c.body}</p>
          </li>
        {/each}
      </ul>
    {/if}

    <div class="flex gap-2 items-end border-t border-border pt-2">
      <div class="relative flex-1">
        <textarea
          class="cmts__input"
          bind:this={textareaEl}
          bind:value={body}
          placeholder="Add a comment… type @ to mention someone (Ctrl/Cmd+Enter to post)"
          rows="2"
          onkeydown={onKeydown}
          oninput={refreshSuggestions}
          onclick={refreshSuggestions}
          onkeyup={refreshSuggestions}
        ></textarea>
        {#if suggestions.length > 0}
          <ul class="cmts__mentions" role="listbox">
            {#each suggestions as u, i (u)}
              <li>
                <button
                  type="button"
                  class="cmts__mention"
                  class:cmts__mention--active={i === selectedIndex}
                  role="option"
                  aria-selected={i === selectedIndex}
                  onmousedown={(e) => {
                    e.preventDefault();
                    void pickMention(u);
                  }}
                >
                  @{u}
                </button>
              </li>
            {/each}
          </ul>
        {/if}
      </div>
      <Button onclick={post} disabled={posting || !body.trim()}>
        <Send size={14} /><span>{posting ? "Posting…" : "Post"}</span>
      </Button>
    </div>
  </div>
</Modal>

<style>
.cmts {
    min-width: 24rem;
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }
  .cmts__state {
    color: var(--fg-muted);
    font-size: var(--fs-sm);
    padding: var(--space-2) 0;
  }
  .cmts__list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    max-height: 40vh;
    overflow: auto;
  }
  .cmts__date {
    font-size: var(--fs-xs, 0.75rem);
    color: var(--fg-muted);
  }
  .cmts__del {
    margin-left: auto;
    background: none;
    border: none;
    color: var(--fg-muted);
    cursor: pointer;
    padding: 2px;
  }
  .cmts__del:hover {
    color: var(--danger);
  }
  .cmts__body {
    margin: var(--space-1) 0 0;
    font-size: var(--fs-sm);
    white-space: pre-wrap;
    word-break: break-word;
  }
  .cmts__input {
    width: 100%;
    box-sizing: border-box;
    resize: vertical;
    font: inherit;
  }
  /* @-mention dropdown, floats above the textarea. */
  .cmts__mentions {
    position: absolute;
    bottom: calc(100% + 2px);
    left: 0;
    right: 0;
    margin: 0;
    padding: 4px;
    list-style: none;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    box-shadow: var(--shadow-md, 0 4px 12px rgba(0, 0, 0, 0.15));
    max-height: 12rem;
    overflow: auto;
    z-index: 10;
  }
  .cmts__mention {
    display: block;
    width: 100%;
    text-align: left;
    padding: 4px var(--space-2);
    border: none;
    background: none;
    border-radius: 4px;
    cursor: pointer;
    font: inherit;
    color: var(--fg);
  }
</style>
