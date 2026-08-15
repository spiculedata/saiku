<script lang="ts">
  /*
   * #941 PR2 — owner-facing "Share" dialog. Mints a time-bounded, account-free
   * read-only link (POST /share/mint), shows the copyable /ui/share#<token> URL
   * (token in the fragment), and lists / revokes this dashboard's active links.
   */
  import { base } from "$app/paths";
  import { Button } from "$lib/components/ui";
  import Modal from "$lib/components/Modal.svelte";
  import { Copy, Check, Trash2 } from "@lucide/svelte";
  import {
    mintShare,
    listShareTokens,
    revokeShareToken,
    type ShareTokenInfo,
  } from "$lib/api/dashboards";
  import { buildShareUrl } from "$lib/dashboard/shareUrl";

  interface Props {
    dashboardPath: string;
    open: boolean;
    onClose: () => void;
  }

  let { dashboardPath, open, onClose }: Props = $props();

  let ttlHours = $state(72);
  let busy = $state(false);
  let error = $state<string | null>(null);
  let mintedUrl = $state<string | null>(null);
  let copied = $state(false);
  let tokens = $state<ShareTokenInfo[]>([]);

  function fullUrl(token: string): string {
    const origin = typeof window !== "undefined" ? window.location.origin : "";
    return buildShareUrl(token, { origin, base });
  }

  async function refresh(): Promise<void> {
    try {
      const all = await listShareTokens();
      tokens = all.filter((t) => t.dashboardPath === dashboardPath && t.active);
    } catch {
      // listing is best-effort; minting still works
    }
  }

  // Reload the active-link list whenever the dialog opens.
  $effect(() => {
    if (open) {
      mintedUrl = null;
      error = null;
      copied = false;
      void refresh();
    }
  });

  async function create(): Promise<void> {
    busy = true;
    error = null;
    copied = false;
    try {
      const res = await mintShare(dashboardPath, ttlHours);
      mintedUrl = fullUrl(res.token);
      await refresh();
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to create share link";
    } finally {
      busy = false;
    }
  }

  async function copy(): Promise<void> {
    if (!mintedUrl) return;
    try {
      await navigator.clipboard.writeText(mintedUrl);
      copied = true;
      setTimeout(() => (copied = false), 1500);
    } catch {
      error = "Could not copy — select the link and copy manually.";
    }
  }

  async function revoke(token: string): Promise<void> {
    try {
      await revokeShareToken(token);
      await refresh();
    } catch (e) {
      error = e instanceof Error ? e.message : "Failed to revoke";
    }
  }

  function fmtDate(ms: number): string {
    try {
      return new Date(ms).toLocaleString();
    } catch {
      return String(ms);
    }
  }
</script>

<Modal title="Share dashboard" {open} size="md" {onClose}>
  <div class="share">
    <p class="text-fg-muted text-sm m-0">
      Anyone with the link can <strong>view</strong> this dashboard read-only — no Saiku
      account needed. Links expire and can be revoked.
    </p>

    <div class="flex items-end gap-3">
      <label class="share__ttl">
        <span>Expires in (hours)</span>
        <input type="number" min="1" max="720" bind:value={ttlHours} />
      </label>
      <Button onclick={create} disabled={busy || !dashboardPath}>
        {busy ? "Creating…" : "Create link"}
      </Button>
    </div>

    {#if error}<p class="text-danger text-sm m-0">{error}</p>{/if}

    {#if mintedUrl}
      <div class="flex gap-2 items-center">
        <input class="share__url" readonly value={mintedUrl} aria-label="Share link" />
        <Button variant="outline" onclick={copy} title="Copy link">
          {#if copied}<Check size={14} />{:else}<Copy size={14} />{/if}
          <span>{copied ? "Copied" : "Copy"}</span>
        </Button>
      </div>
    {/if}

    {#if tokens.length > 0}
      <div class="share__list">
        <span class="text-sm text-fg-muted font-bold">Active links</span>
        {#each tokens as t (t.token)}
          <div class="share__row">
            <span class="text-sm text-fg-muted">expires {fmtDate(t.expiresAt)}</span>
            <Button variant="destructive" size="sm" onclick={() => revoke(t.token)} title="Revoke this link">
              <Trash2 size={13} /><span>Revoke</span>
            </Button>
          </div>
        {/each}
      </div>
    {/if}
  </div>
</Modal>

<style>
.share {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    min-width: 22rem;
  }
  .share__ttl {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    font-size: var(--fs-sm);
    color: hsl(var(--fg-muted));
  }
  .share__ttl input {
    width: 6rem;
  }
  .share__url {
    flex: 1;
    font-family: var(--font-mono, monospace);
    font-size: var(--fs-sm);
  }
  .share__list {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    border-top: 1px solid hsl(var(--border));
    padding-top: var(--space-2);
  }
  .share__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
  }
</style>
