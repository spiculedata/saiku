<script lang="ts">
  import { onMount } from "svelte";
  import { session } from "$lib/stores/session.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { platform } from "$lib/stores/platform.svelte";
  import ApiAccessAdmin from "$lib/views/admin/ApiAccessAdmin.svelte";

  let username = $state("admin");
  let password = $state("");
  let error = $state<string | null>(null);
  let busy = $state(false);

  // Anonymous-accessible probe — surfaces demoMode + AI/MCP URLs so the
  // login screen of a demo deployment can show visitors how to drive the
  // API or wire the MCP server before they ever sign in. On a normal
  // (non-demo) deployment this panel stays hidden — Saiku admins see it
  // only after auth via the /admin "API access" tab.
  onMount(() => {
    if (!platform.capabilities) {
      platform.loadCapabilities();
    }
  });

  const showDemoPanel = $derived(platform.capabilities?.demoMode === true);

  async function onSubmit(e: SubmitEvent) {
    e.preventDefault();
    error = null;
    busy = true;
    try {
      await session.login(username, password);
    } catch (err) {
      error = err instanceof Error ? err.message : i18n.t("login.failed");
    } finally {
      busy = false;
    }
  }
</script>

<div class="login-stack">
  <form class="login" onsubmit={onSubmit}>
    <h1>{i18n.t("login.title")}</h1>
    {#if showDemoPanel}
      <p class="demo-creds">
        Demo credentials: <code>admin</code> / <code>admin</code>. Data resets nightly.
      </p>
    {/if}
    {#if error}
      <p class="callout callout--danger" role="alert">{error}</p>
    {/if}
    <label class="field">
      <span class="field__label">{i18n.t("login.username")}</span>
      <input class="field__input" bind:value={username} autocomplete="username" required />
    </label>
    <label class="field">
      <span class="field__label">{i18n.t("login.password")}</span>
      <input
        class="field__input"
        type="password"
        bind:value={password}
        autocomplete="current-password"
        required
      />
    </label>
    <button type="submit" class="btn btn--primary btn--wide" disabled={busy}>
      {busy ? i18n.t("login.submitting") : i18n.t("login.submit")}
    </button>
  </form>

  {#if showDemoPanel}
    <aside class="login-stack__demo">
      <ApiAccessAdmin />
    </aside>
  {/if}
</div>

<style>
  .login-stack {
    /* .app__main wraps us in overflow:hidden + flex:1 (right for the
     * post-login workspace, wrong for our tall connection-info panel).
     * Take the available height and manage our own y-scroll so users
     * can reach the DXT download / accordion below the fold. */
    flex: 1;
    align-self: stretch;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--space-5);
    padding: var(--space-4);
    width: 100%;
    max-width: 80rem;
    margin-inline: auto;
  }
  .login {
    padding: var(--space-6);
    max-width: 380px;
    width: 100%;
    background: var(--bg-muted);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-md);
  }
  .login h1 {
    margin: 0 0 var(--space-4);
    font-size: var(--fs-xl);
  }
  .demo-creds {
    margin: 0 0 var(--space-3);
    padding: var(--space-2) var(--space-3);
    background: var(--accent-soft, #ecfdf5);
    color: var(--accent-strong, #047857);
    border-left: 3px solid var(--accent, #10b981);
    font-size: 0.85rem;
  }
  .login-stack__demo {
    width: 100%;
    padding: var(--space-4);
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
  }
  .btn--wide {
    width: 100%;
    justify-content: center;
  }
</style>
