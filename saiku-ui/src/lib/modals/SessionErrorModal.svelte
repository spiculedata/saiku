<script lang="ts">
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { session } from "$lib/stores/session.svelte";
  import { hasPendingOps, notifySessionResumed } from "$lib/api/http";
  import { toasts } from "$lib/stores/toasts.svelte";

  /** Port of saiku-ui-legacy/js/saiku/views/SessionErrorModal.js.
   *  Now offers in-place re-authentication: once the user signs in we close
   *  the modal and replay any in-flight requests that were queued at the
   *  http layer, so the canvas / query state is preserved.
   */
  interface Props {
    message: string;
    open: boolean;
    /** Fallback — full reload if the user dismisses without re-authenticating. */
    onReload: () => void;
  }

  let { message, open, onReload }: Props = $props();

  let username = $state("");
  let password = $state("");
  let busy = $state(false);
  let err = $state<string | null>(null);

  $effect(() => {
    // Seed username from the last-known session whenever the modal opens.
    if (open) {
      username = session.current?.username ?? username;
      password = "";
      err = null;
    }
  });

  async function onSubmit(e: SubmitEvent) {
    e.preventDefault();
    busy = true;
    err = null;
    try {
      await session.login(username, password);
      const hadPending = hasPendingOps();
      notifySessionResumed();
      if (hadPending) toasts.success("Resumed", "Replaying your last action.");
      else toasts.success("Signed in");
      // Caller sets open=false by listening for onResumed below.
      onResumed();
    } catch (ex) {
      err = ex instanceof Error ? ex.message : i18n.t("login.failed", "Sign-in failed");
    } finally {
      busy = false;
    }
  }

  function onResumed(): void {
    // Close the modal by signalling the parent. We reuse onReload's slot but
    // skip the actual reload — the parent should zero out sessionError state.
    // The +layout.svelte onReload callback already just closes the modal and
    // calls location.reload(); we need a variant that does NOT reload. So we
    // set a flag via a dispatched event the parent listens for.
    dispatchResumed();
  }

  function dispatchResumed() {
    // Emit a window event the layout subscribes to. Avoids adding another prop.
    window.dispatchEvent(new CustomEvent("saiku-session-resumed"));
  }
</script>

<Modal title={i18n.t("modal.session.title")} {open} size="sm" onClose={onReload}>
  <p class="callout callout--danger">{message}</p>
  <p>Your session ended — sign in and we'll keep going.</p>
  <form class="resume-login" onsubmit={onSubmit}>
    {#if err}
      <p class="callout callout--danger" role="alert">{err}</p>
    {/if}
    <label class="field">
      <span class="field__label">{i18n.t("login.username")}</span>
      <input
        class="field__input"
        bind:value={username}
        autocomplete="username"
        required
        disabled={busy}
      />
    </label>
    <label class="field">
      <span class="field__label">{i18n.t("login.password")}</span>
      <input
        class="field__input"
        type="password"
        bind:value={password}
        autocomplete="current-password"
        required
        disabled={busy}
      />
    </label>
    <div class="resume-login__actions">
      <button type="button" class="btn" onclick={onReload} disabled={busy}>
        {i18n.t("modal.session.reload")}
      </button>
      <button type="submit" class="btn btn--primary" disabled={busy}>
        {busy ? i18n.t("login.submitting") : i18n.t("login.submit")}
      </button>
    </div>
  </form>
  {#snippet footer()}
    <span class="footer-hint">Press Enter to sign in.</span>
  {/snippet}
</Modal>

<style>
  .resume-login {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    margin-top: var(--space-3);
  }
  .resume-login__actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-2);
    margin-top: var(--space-2);
  }
  .footer-hint {
    color: var(--fg-muted);
    font-size: var(--fs-xs);
  }
</style>
