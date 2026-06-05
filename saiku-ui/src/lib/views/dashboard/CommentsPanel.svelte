<script lang="ts">
  /*
   * #942 PR2 — per-tile comments thread. Lists comments for one tile, lets the
   * user post (plain @username typing — mentions are parsed server-side;
   * autocomplete deferred until a non-admin users-list endpoint exists), and
   * delete their own (admins delete any).
   */
  import Modal from "$lib/components/Modal.svelte";
  import { Trash2, Send } from "lucide-svelte";
  import { session } from "$lib/stores/session.svelte";
  import { getComments, postComment, deleteComment, type DashboardComment } from "$lib/api/dashboards";

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

  function onKeydown(e: KeyboardEvent): void {
    // Ctrl/Cmd+Enter posts.
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
      <p class="cmts__state cmts__state--error">{error}</p>
    {:else if comments.length === 0}
      <p class="cmts__state">No comments yet. Start the conversation about this tile.</p>
    {:else}
      <ul class="cmts__list">
        {#each comments as c (c.id)}
          <li class="cmts__item">
            <div class="cmts__head">
              <span class="cmts__author">{c.author}</span>
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

    <div class="cmts__compose">
      <textarea
        class="cmts__input"
        bind:value={body}
        placeholder="Add a comment… use @name to mention someone (Ctrl/Cmd+Enter to post)"
        rows="2"
        onkeydown={onKeydown}
      ></textarea>
      <button type="button" class="btn primary" onclick={post} disabled={posting || !body.trim()}>
        <Send size={14} /><span>{posting ? "Posting…" : "Post"}</span>
      </button>
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
  .cmts__state--error {
    color: var(--danger);
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
  .cmts__item {
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    padding: var(--space-2);
    background: var(--bg-subtle);
  }
  .cmts__head {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  .cmts__author {
    font-weight: var(--weight-bold);
    font-size: var(--fs-sm);
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
  .cmts__compose {
    display: flex;
    gap: var(--space-2);
    align-items: flex-end;
    border-top: 1px solid var(--border);
    padding-top: var(--space-2);
  }
  .cmts__input {
    flex: 1;
    resize: vertical;
    font: inherit;
  }
</style>
