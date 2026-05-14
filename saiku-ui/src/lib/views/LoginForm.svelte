<script lang="ts">
  import { session } from "$lib/stores/session.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  let username = $state("admin");
  let password = $state("");
  let error = $state<string | null>(null);
  let busy = $state(false);

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

<form class="login" onsubmit={onSubmit}>
  <h1>{i18n.t("login.title")}</h1>
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

<style>
  .login {
    margin: auto;
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
  .btn--wide {
    width: 100%;
    justify-content: center;
  }
</style>
