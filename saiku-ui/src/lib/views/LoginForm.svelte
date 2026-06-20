<script lang="ts">
  import { onMount } from "svelte";
  import { Button } from "$lib/components/ui";
  import { session } from "$lib/stores/session.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { platform } from "$lib/stores/platform.svelte";
  import { gateStatus } from "$lib/api/demoGate";
  import ApiAccessAdmin from "$lib/views/admin/ApiAccessAdmin.svelte";
  import EmailGateForm from "$lib/views/EmailGateForm.svelte";
  import { Info } from "lucide-svelte";

  let username = $state("admin");
  let password = $state("");
  let error = $state<string | null>(null);
  let busy = $state(false);

  // saiku#1029: when the demo email gate is on, show it before the login form.
  // gateCleared starts true (no gate); flipped false on mount if the gate is
  // enabled and this visitor hasn't verified yet (cookie absent).
  let gateCleared = $state(true);
  const emailGate = $derived(platform.capabilities?.emailGate === true);

  // Anonymous-accessible probe — surfaces demoMode + AI/MCP URLs so the
  // login screen of a demo deployment can show visitors how to drive the
  // API or wire the MCP server before they ever sign in. On a normal
  // (non-demo) deployment this panel stays hidden — Saiku admins see it
  // only after auth via the /admin "API access" tab.
  onMount(async () => {
    if (!platform.capabilities) {
      await platform.loadCapabilities();
    }
    // If the email gate is on, hide the login form until the visitor has
    // verified. A returning visitor with a valid cookie is reported verified
    // by /demo/gate/status and skips the gate.
    if (platform.capabilities?.emailGate) {
      gateCleared = false;
      try {
        const s = await gateStatus();
        gateCleared = s.verified;
      } catch {
        gateCleared = false;
      }
    }
    // Prefill the demo password the moment the capabilities probe confirms
    // demo mode. Username already defaults to "admin"; pre-filling the
    // password lets a visitor click Sign in without first having to dig
    // the credentials out of the connection-info panel. On a real
    // deployment demoMode is false and this stays a no-op.
    if (platform.capabilities?.demoMode && !password) {
      password = "admin";
    }
  });

  const showDemoPanel = $derived(platform.capabilities?.demoMode === true);

  async function onGateVerified(): Promise<void> {
    gateCleared = true;
    // The gate only runs in demo mode, where admin/admin is the public
    // credential — sign straight in so a verified visitor lands in the app
    // instead of bouncing to the login form. If that somehow fails, the
    // revealed login form (gateCleared = true) is the graceful fallback.
    await loginAsDemo();
  }

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

  async function loginAsDemo() {
    username = "admin";
    password = "admin";
    error = null;
    busy = true;
    try {
      await session.login("admin", "admin");
    } catch (err) {
      error = err instanceof Error ? err.message : i18n.t("login.failed");
    } finally {
      busy = false;
    }
  }
</script>

<div class="login-stack">
  {#if emailGate && !gateCleared}
    <EmailGateForm onVerified={onGateVerified} />
  {:else}
  <form class="login" onsubmit={onSubmit}>
    <h1>{i18n.t("login.title")}</h1>
    <p class="login__tagline">Semantic Layer analytics for cubes — drag, drop, drill.</p>
    {#if showDemoPanel}
      <div class="demo-creds">
        <Info size={16} class="demo-creds__icon" />
        <div class="demo-creds__body">
          <strong>Try the demo</strong>
          <span>
            Sign in with <code>admin</code> / <code>admin</code>, or use the
            shortcut below. Data resets nightly.
          </span>
        </div>
      </div>
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
    <Button class="w-full" type="submit" disabled={busy}>
      {busy ? i18n.t("login.submitting") : i18n.t("login.submit")}
    </Button>
    {#if showDemoPanel}
      <Button variant="outline" class="w-full login__demo-button" onclick={loginAsDemo} disabled={busy}>
        Sign in as demo user
      </Button>
    {/if}
  </form>

  {#if showDemoPanel}
    <aside class="login-stack__demo">
      <ApiAccessAdmin />
    </aside>
  {/if}
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
    margin: 0 0 var(--space-1);
    font-size: var(--fs-xl);
  }
  .login__tagline {
    margin: 0 0 var(--space-4);
    color: var(--fg-muted);
    font-size: var(--fs-sm);
    line-height: var(--lh-normal);
  }
  .demo-creds {
    display: flex;
    gap: var(--space-2);
    align-items: flex-start;
    margin: 0 0 var(--space-3);
    padding: var(--space-3);
    background: var(--success-soft);
    color: var(--success-strong);
    border-left: 3px solid var(--success);
    border-radius: var(--radius-sm);
    font-size: var(--fs-sm);
    line-height: var(--lh-normal);
  }
  .demo-creds__body {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .demo-creds :global(.demo-creds__icon) {
    flex-shrink: 0;
    margin-top: 2px;
  }
  .login-stack__demo {
    width: 100%;
    padding: var(--space-4);
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
  }
</style>
