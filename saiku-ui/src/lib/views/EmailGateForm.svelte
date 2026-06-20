<script lang="ts">
  /*
   * Demo email gate (saiku#1029). Two steps — collect an email, then the
   * 6-digit code WorkOS Magic Auth emails — shown in front of the login form on
   * a demo deployment. On success the server has set the HttpOnly
   * saiku_demo_verified cookie and we call onVerified() so LoginForm swaps in
   * the real login form. All flow logic lives in $lib/demo/emailGate (tested);
   * this component is the thin shell.
   */
  import { i18n } from "$lib/stores/i18n.svelte";
  import { Button } from "$lib/components/ui";
  import { requestCode, verifyCode } from "$lib/api/demoGate";
  import { isCompleteCode, isValidEmail, normalizeCode, type GateStep } from "$lib/demo/emailGate";
  import { MailCheck } from "lucide-svelte";

  interface Props {
    onVerified: () => void;
  }
  let { onVerified }: Props = $props();

  let step = $state<GateStep>("email");
  let firstName = $state("");
  let lastName = $state("");
  let email = $state("");
  let code = $state("");
  let error = $state<string | null>(null);
  let busy = $state(false);

  const emailValid = $derived(isValidEmail(email));
  const canSend = $derived(emailValid && firstName.trim().length > 0 && lastName.trim().length > 0);
  const codeComplete = $derived(isCompleteCode(code));

  async function onSendCode(e: SubmitEvent) {
    e.preventDefault();
    if (!canSend || busy) return;
    error = null;
    busy = true;
    try {
      await requestCode(email.trim());
      step = "code";
    } catch (err) {
      error = err instanceof Error ? err.message : i18n.t("demoGate.sendFailed");
    } finally {
      busy = false;
    }
  }

  async function onVerify(e: SubmitEvent) {
    e.preventDefault();
    if (!codeComplete || busy) return;
    error = null;
    busy = true;
    try {
      await verifyCode(email.trim(), code, firstName.trim(), lastName.trim());
      onVerified();
    } catch (err) {
      error = err instanceof Error ? err.message : i18n.t("demoGate.verifyFailed");
    } finally {
      busy = false;
    }
  }

  function onCodeInput(e: Event) {
    code = normalizeCode((e.target as HTMLInputElement).value);
  }

  function back() {
    step = "email";
    code = "";
    error = null;
  }
</script>

<div class="gate">
  <div class="gate__badge">
    <MailCheck size={20} />
  </div>
  <h1>{i18n.t("demoGate.title")}</h1>
  <p class="gate__intro">{i18n.t("demoGate.intro")}</p>

  {#if error}
    <p class="callout callout--danger" role="alert">{error}</p>
  {/if}

  {#if step === "email"}
    <form onsubmit={onSendCode}>
      <div class="gate__names">
        <label class="field">
          <span class="field__label">{i18n.t("demoGate.firstName")}</span>
          <input
            class="field__input"
            bind:value={firstName}
            placeholder={i18n.t("demoGate.firstNamePlaceholder")}
            autocomplete="given-name"
            required
          />
        </label>
        <label class="field">
          <span class="field__label">{i18n.t("demoGate.lastName")}</span>
          <input
            class="field__input"
            bind:value={lastName}
            placeholder={i18n.t("demoGate.lastNamePlaceholder")}
            autocomplete="family-name"
            required
          />
        </label>
      </div>
      <label class="field">
        <span class="field__label">{i18n.t("demoGate.emailLabel")}</span>
        <input
          class="field__input"
          type="email"
          bind:value={email}
          placeholder={i18n.t("demoGate.emailPlaceholder")}
          autocomplete="email"
          required
        />
      </label>
      <Button class="w-full" type="submit" disabled={busy || !canSend}>
        {busy ? i18n.t("demoGate.sending") : i18n.t("demoGate.sendCode")}
      </Button>
    </form>
  {:else}
    <form onsubmit={onVerify}>
      <p class="gate__sent">{i18n.t("demoGate.codeSent")} <strong>{email}</strong></p>
      <label class="field">
        <span class="field__label">{i18n.t("demoGate.codeLabel")}</span>
        <input
          class="field__input gate__code"
          inputmode="numeric"
          autocomplete="one-time-code"
          value={code}
          oninput={onCodeInput}
          placeholder="••••••"
          required
        />
      </label>
      <Button class="w-full" type="submit" disabled={busy || !codeComplete}>
        {busy ? i18n.t("demoGate.verifying") : i18n.t("demoGate.verify")}
      </Button>
      <Button variant="outline" class="w-full gate__back" onclick={back} disabled={busy}>
        {i18n.t("demoGate.back")}
      </Button>
    </form>
  {/if}

  <p class="gate__privacy">{i18n.t("demoGate.privacy")}</p>
</div>

<style>
  .gate {
    padding: var(--space-6);
    max-width: 380px;
    width: 100%;
    background: var(--bg-muted);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-md);
  }
  .gate__badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border-radius: var(--radius-md);
    background: var(--success-soft);
    color: var(--success-strong);
    margin-bottom: var(--space-3);
  }
  .gate h1 {
    margin: 0 0 var(--space-1);
    font-size: var(--fs-xl);
  }
  .gate__intro {
    margin: 0 0 var(--space-4);
    color: var(--fg-muted);
    font-size: var(--fs-sm);
    line-height: var(--lh-normal);
  }
  .gate__names {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--space-2);
  }
  .gate__sent {
    margin: 0 0 var(--space-3);
    font-size: var(--fs-sm);
    color: var(--fg-muted);
  }
  .gate__code {
    letter-spacing: 0.4em;
    font-size: var(--fs-lg);
    text-align: center;
  }
  .gate__privacy {
    margin: var(--space-4) 0 0;
    font-size: var(--fs-xs);
    color: var(--fg-muted);
    line-height: var(--lh-normal);
  }
</style>
