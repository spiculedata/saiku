<script lang="ts">
  import { untrack } from "svelte";
  import Modal from "$lib/components/Modal.svelte";
  import { Button } from "$lib/components/ui";
  import { AlertCircle, Loader2 } from "lucide-svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { renderTinyMarkdown } from "$lib/api/tinyMarkdown";
  import { aiInsight } from "$lib/stores/aiInsight.svelte";
  import { chartPngBase64, resultToPdfBase64 } from "$lib/email/artifacts";
  import { sendEmailSelf } from "$lib/api/emailSelf";
  import { rememberedAddress } from "$lib/email/rememberedAddress";
  import { query } from "$lib/stores/query.svelte";

  /*
   * "Email me this" — bundles the current AI insight (rendered to HTML),
   * the chart PNG (chart view mode only), and a PDF snapshot of the result
   * view, then POSTs them to the backend's send-to-self endpoint.
   *
   * Structural template is ReportTitlesModal.svelte: local $state form
   * seeded via $effect from props, Modal + field__input inputs, a footer
   * snippet with outline Cancel + default Send buttons.
   */

  interface Props {
    open: boolean;
    onClose: () => void;
  }

  let { open, onClose }: Props = $props();

  let address = $state(untrack(() => rememberedAddress.get()));
  let subject = $state(untrack(() => defaultSubject()));
  let sending = $state(false);
  let showAddressError = $state(false);

  function defaultSubject(): string {
    const cubeName = query.current?.cube?.caption ?? query.current?.cube?.name;
    return cubeName ? `Saiku analysis — ${cubeName}` : i18n.t("modal.emailMeThis.defaultSubject", "Saiku analysis");
  }

  $effect(() => {
    if (open) {
      address = rememberedAddress.get();
      subject = defaultSubject();
      showAddressError = false;
    }
  });

  const addressValid = $derived(address.trim().length > 0);

  async function handleSend() {
    if (!addressValid) {
      showAddressError = true;
      return;
    }
    sending = true;
    try {
      const body = {
        subject,
        address,
        summaryHtml: aiInsight.latestMarkdown ? renderTinyMarkdown(aiInsight.latestMarkdown) : "",
        chartPngBase64: chartPngBase64(),
        pdfBase64: await resultToPdfBase64(document.querySelector(".result-host")),
      };
      rememberedAddress.set(address);
      const res = await sendEmailSelf(body);
      if (res.ok) {
        toasts.success(
          i18n.t("modal.emailMeThis.sentTitle", "Email sent"),
          `${i18n.t("modal.emailMeThis.sentBody", "Sent to")} ${address}`,
        );
        onClose();
        return;
      }
      if (res.status === 503) {
        toasts.danger(
          i18n.t("modal.emailMeThis.notConfiguredTitle", "Email not configured"),
          i18n.t(
            "modal.emailMeThis.notConfiguredBody",
            "This server has no mail sender set.",
          ),
        );
        return;
      }
      if (res.status === 400) {
        const message = await extractErrorMessage(res);
        toasts.danger(
          i18n.t("modal.emailMeThis.badRequestTitle", "Couldn't send"),
          message ?? i18n.t("modal.emailMeThis.badRequestBody", "Check the recipient address and try again."),
        );
        return;
      }
      toasts.danger(
        i18n.t("modal.emailMeThis.failedTitle", "Send failed"),
        i18n.t("modal.emailMeThis.failedBody", "Something went wrong while sending. Please try again."),
      );
    } catch (err) {
      toasts.danger(
        i18n.t("modal.emailMeThis.failedTitle", "Send failed"),
        err instanceof Error ? err.message : i18n.t("modal.emailMeThis.failedBody", "Something went wrong while sending. Please try again."),
      );
    } finally {
      sending = false;
    }
  }

  /** Best-effort extraction of a server-provided error message from a 400
   *  response body. EmailResource.sendSelf() replies with {"error": "..."}
   *  (see EmailRequestException handling) — "message" is also checked for
   *  robustness against other error shapes. Falls back to undefined (caller
   *  supplies a generic message) if the body isn't JSON or has no
   *  recognizable field. */
  async function extractErrorMessage(res: Response): Promise<string | undefined> {
    try {
      const data = (await res.clone().json()) as { error?: string; message?: string };
      return data.error || data.message || undefined;
    } catch {
      return undefined;
    }
  }
</script>

<Modal title={i18n.t("modal.emailMeThis.title", "Email me this")} {open} size="md" onClose={onClose}>
  <label class="field" class:field--invalid={showAddressError}>
    <span class="field__label">{i18n.t("modal.emailMeThis.to", "To")}</span>
    <input
      class="field__input"
      type="email"
      bind:value={address}
      oninput={() => (showAddressError = false)}
      autocomplete="email"
    />
    {#if showAddressError}
      <p class="field__error"><AlertCircle size={14} />{i18n.t("modal.emailMeThis.addressRequired", "Enter an email address.")}</p>
    {/if}
  </label>
  <label class="field">
    <span class="field__label">{i18n.t("modal.emailMeThis.subject", "Subject")}</span>
    <input class="field__input" bind:value={subject} />
  </label>
  {#snippet footer()}
    <Button variant="outline" onclick={onClose} disabled={sending}>{i18n.t("modal.cancel", "Cancel")}</Button>
    <Button onclick={handleSend} disabled={sending || !addressValid}>
      {#if sending}
        <Loader2 size={14} class="animate-spin" />
        {i18n.t("modal.emailMeThis.sending", "Sending…")}
      {:else}
        {i18n.t("modal.emailMeThis.send", "Send")}
      {/if}
    </Button>
  {/snippet}
</Modal>
