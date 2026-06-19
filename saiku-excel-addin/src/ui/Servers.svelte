<script lang="ts">
  import { onMount } from "svelte";
  import type { ServerEntry, Capabilities } from "../lib/types";
  import type { Registry } from "../lib/serverRegistry";
  import { addServer, removeServer, setActive, normalizeBaseUrl } from "../lib/serverRegistry";
  import { fetchCapabilities, chooseStrategies } from "../lib/capabilities";
  import { saveCredential, clearCredential, loadCredential } from "../lib/credentialStore";
  import { saveRegistry, loadRegistry } from "../lib/queryStore";
  import { signIn } from "../lib/workosOAuth";

  const { onClose }: { onClose: () => void } = $props();

  let registry = $state<Registry>({ servers: [], activeId: null });
  let loadError = $state<string | null>(null);

  // Add-server form state
  let addUrl = $state("");
  let addLabel = $state("");
  let addError = $state<string | null>(null);
  let probing = $state(false);

  // Basic credential form (shown after capabilities probe)
  let pendingEntry = $state<ServerEntry | null>(null);
  let pendingCaps = $state<Capabilities | null>(null);
  let basicUser = $state("");
  let basicPass = $state("");
  let savingCred = $state(false);
  let credError = $state<string | null>(null);

  // Per-server status
  let serverStatus = $state<Record<string, string>>({});

  onMount(async () => {
    try {
      registry = await loadRegistry();
      // Load connection status labels for each server
      for (const s of registry.servers) {
        const cred = await loadCredential(s.id);
        serverStatus = { ...serverStatus, [s.id]: cred ? "Connected" : "Not signed in" };
      }
    } catch (e) {
      loadError = e instanceof Error ? e.message : "Failed to load servers";
    }
  });

  async function handleProbe() {
    addError = null;
    const baseUrl = normalizeBaseUrl(addUrl);
    if (!baseUrl) { addError = "Enter a server URL"; return; }
    const label = addLabel.trim() || baseUrl.replace(/^https?:\/\//, "");

    probing = true;
    try {
      const caps = await fetchCapabilities(baseUrl);
      const strategies = chooseStrategies(caps);
      if (strategies.length === 0) {
        addError = "This server doesn't support Basic or OIDC auth cross-origin. Check the server's /auth-capabilities endpoint.";
        return;
      }
      const entry: ServerEntry = {
        id: crypto.randomUUID(),
        label,
        baseUrl,
        strategy: strategies[0] as "basic" | "oidc"
      };
      pendingEntry = entry;
      pendingCaps = caps;
      // If oidc preferred, kick off SSO immediately
      if (strategies[0] === "oidc" && caps.oidc) {
        await handleOidcSignIn(entry, caps.oidc);
      }
      // If basic, show the form (handled in template)
    } catch (e) {
      if (e instanceof TypeError) {
        addError = "Can't reach this server, or it hasn't allowed the add-in (CORS). Check the server URL and CORS config.";
      } else {
        addError = e instanceof Error ? e.message : "Probe failed";
      }
    } finally {
      probing = false;
    }
  }

  async function handleOidcSignIn(entry: ServerEntry, oidcInfo: import("../lib/types").OidcInfo) {
    savingCred = true;
    credError = null;
    try {
      const cred = await signIn(oidcInfo);
      await saveCredential(entry.id, cred);
      await commitServer(entry);
    } catch (e) {
      credError = e instanceof Error ? e.message : "Sign-in failed";
    } finally {
      savingCred = false;
    }
  }

  async function handleBasicSave() {
    if (!pendingEntry) return;
    if (!basicUser.trim()) { credError = "Username is required"; return; }
    savingCred = true;
    credError = null;
    try {
      await saveCredential(pendingEntry.id, { kind: "basic", username: basicUser.trim(), password: basicPass });
      await commitServer(pendingEntry);
    } catch (e) {
      credError = e instanceof Error ? e.message : "Failed to save credential";
    } finally {
      savingCred = false;
    }
  }

  async function commitServer(entry: ServerEntry) {
    const next = addServer(registry, entry);
    registry = next;
    await saveRegistry(next);
    serverStatus = { ...serverStatus, [entry.id]: "Connected" };
    // Reset form
    addUrl = "";
    addLabel = "";
    pendingEntry = null;
    pendingCaps = null;
    basicUser = "";
    basicPass = "";
  }

  async function handleRemove(id: string) {
    try {
      await clearCredential(id);
      const next = removeServer(registry, id);
      registry = next;
      await saveRegistry(next);
      const { [id]: _removed, ...rest } = serverStatus;
      serverStatus = rest;
    } catch (e) {
      loadError = e instanceof Error ? e.message : "Failed to remove server";
    }
  }

  async function handleSetActive(id: string) {
    const next = setActive(registry, id);
    registry = next;
    await saveRegistry(next);
  }

  function cancelAdd() {
    pendingEntry = null;
    pendingCaps = null;
    addUrl = "";
    addLabel = "";
    addError = null;
    credError = null;
    basicUser = "";
    basicPass = "";
  }
</script>

<div class="servers">
  <div class="servers-header">
    <h2>Servers</h2>
    {#if registry.servers.length > 0}
      <button class="btn-ghost" onclick={onClose}>← Back</button>
    {/if}
  </div>

  {#if loadError}
    <div class="error" role="alert">{loadError}</div>
  {/if}

  {#if registry.servers.length === 0 && !pendingEntry}
    <div class="empty">
      <p>No servers added yet.</p>
      <p>Add a Saiku server URL to get started.</p>
    </div>
  {:else if registry.servers.length > 0}
    <ul class="server-list">
      {#each registry.servers as s (s.id)}
        <li class="server-item" class:active={s.id === registry.activeId}>
          <div class="server-info">
            <span class="server-label">{s.label}</span>
            <span class="server-url">{s.baseUrl}</span>
            <span class="server-status" class:connected={serverStatus[s.id] === "Connected"}>
              {serverStatus[s.id] ?? "Unknown"}
            </span>
          </div>
          <div class="server-actions">
            {#if s.id !== registry.activeId}
              <button class="btn-small" onclick={() => handleSetActive(s.id)}>Use</button>
            {:else}
              <span class="active-badge">Active</span>
            {/if}
            <button class="btn-small btn-danger" onclick={() => handleRemove(s.id)} title="Remove">✕</button>
          </div>
        </li>
      {/each}
    </ul>
  {/if}

  <!-- Add server form -->
  {#if !pendingEntry}
    <div class="add-section">
      <h3>Add a server</h3>
      <div class="add-row">
        <input
          type="url"
          placeholder="https://acme.saiku.io"
          bind:value={addUrl}
          disabled={probing}
        />
      </div>
      <div class="add-row">
        <input
          type="text"
          placeholder="Label (optional)"
          bind:value={addLabel}
          disabled={probing}
        />
      </div>
      {#if addError}
        <div class="error" role="alert">{addError}</div>
      {/if}
      <button class="btn-primary" onclick={handleProbe} disabled={probing || !addUrl.trim()}>
        {probing ? "Connecting…" : "Connect"}
      </button>
    </div>
  {:else if pendingEntry && pendingCaps}
    {@const needsBasic = pendingCaps.basic && pendingEntry.strategy === "basic"}
    {@const needsOidc = pendingEntry.strategy === "oidc" && !!pendingCaps.oidc}
    <div class="auth-section">
      <h3>Sign in to {pendingEntry.label}</h3>
      {#if needsOidc}
        <p class="hint">Opening SSO sign-in…</p>
        {#if credError}
          <div class="error" role="alert">{credError}</div>
          <button class="btn-primary" onclick={() => pendingCaps?.oidc && handleOidcSignIn(pendingEntry!, pendingCaps.oidc)} disabled={savingCred}>
            Retry SSO
          </button>
        {/if}
      {:else if needsBasic}
        <div class="add-row">
          <input type="text" placeholder="Username" bind:value={basicUser} disabled={savingCred} />
        </div>
        <div class="add-row">
          <input type="password" placeholder="Password" bind:value={basicPass} disabled={savingCred} />
        </div>
        {#if credError}
          <div class="error" role="alert">{credError}</div>
        {/if}
        <div class="form-actions">
          <button class="btn-ghost" onclick={cancelAdd} disabled={savingCred}>Cancel</button>
          <button class="btn-primary" onclick={handleBasicSave} disabled={savingCred || !basicUser.trim()}>
            {savingCred ? "Saving…" : "Save"}
          </button>
        </div>
      {/if}
    </div>
  {/if}
</div>

<style>
  .servers {
    padding: var(--space-3) var(--space-4);
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .servers-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  h2 {
    font-size: var(--fs-md);
    font-weight: var(--fw-bold);
    margin: 0;
    color: var(--fg);
  }

  h3 {
    font-size: var(--fs-sm);
    font-weight: var(--fw-semibold);
    margin: 0;
    color: var(--fg);
  }

  .empty {
    text-align: center;
    color: var(--fg-muted);
    font-size: var(--fs-sm);
    padding: var(--space-5) 0;
  }

  .error {
    font-size: var(--fs-sm);
    color: var(--danger);
    padding: 6px 8px;
    background: var(--danger-soft);
    border-radius: var(--radius-sm);
  }

  .server-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }

  .server-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--space-2) 10px;
    background: var(--bg-muted);
    border-radius: var(--radius-sm);
    border: 1px solid var(--border);
    gap: var(--space-2);
  }

  .server-item.active {
    border-color: var(--accent);
    background: var(--accent-soft);
  }

  .server-info {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
    overflow: hidden;
  }

  .server-label {
    font-size: var(--fs-sm);
    font-weight: var(--fw-semibold);
    color: var(--fg);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .server-url {
    font-size: var(--fs-xs);
    color: var(--fg-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .server-status {
    font-size: var(--fs-xs);
    color: var(--fg-subtle);
  }

  .server-status.connected {
    color: var(--success);
  }

  .server-actions {
    display: flex;
    gap: var(--space-1);
    align-items: center;
    flex-shrink: 0;
  }

  .active-badge {
    font-size: var(--fs-xs);
    color: var(--accent);
    font-weight: var(--fw-semibold);
    padding: 2px 7px;
  }

  .add-section, .auth-section {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3) 10px;
    background: var(--bg-muted);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }

  .add-row {
    display: flex;
    gap: var(--space-1);
  }

  .add-row input {
    font-family: var(--font);
    font-size: var(--fs-sm);
    flex: 1;
    padding: 5px 8px;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    outline: none;
    color: var(--fg);
    background: var(--bg);
  }

  .add-row input:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
  .add-row input:disabled { background: var(--bg-subtle); }

  .hint {
    font-size: var(--fs-sm);
    color: var(--fg-muted);
    margin: 0;
  }

  .form-actions {
    display: flex;
    gap: var(--space-2);
    justify-content: flex-end;
  }

  .btn-primary {
    font-family: var(--font);
    font-size: var(--fs-xs);
    font-weight: var(--fw-semibold);
    color: var(--on-accent);
    background: var(--accent);
    border: none;
    border-radius: var(--radius-sm);
    padding: 5px 12px;
    cursor: pointer;
    box-shadow: var(--shadow-sm);
    align-self: flex-start;
  }

  .btn-primary:hover:not(:disabled) { background: var(--accent-hover); }
  .btn-primary:disabled { background: var(--border-strong); cursor: default; }

  .btn-small {
    font-family: var(--font);
    font-size: var(--fs-xs);
    color: var(--fg);
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    padding: 2px 7px;
    cursor: pointer;
  }

  .btn-small:hover { background: var(--bg-muted); }

  .btn-danger { color: var(--danger); border-color: var(--danger); }
  .btn-danger:hover { background: var(--danger-soft); }

  .btn-ghost {
    font-family: var(--font);
    font-size: var(--fs-sm);
    color: var(--fg-muted);
    background: none;
    border: none;
    cursor: pointer;
    padding: 4px 0;
    text-decoration: underline;
  }

  .btn-ghost:hover { color: var(--fg); }
  .btn-ghost:disabled { color: var(--fg-subtle); cursor: default; }
</style>
