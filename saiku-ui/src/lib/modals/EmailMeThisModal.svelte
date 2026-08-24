<script lang="ts">
	import { untrack } from 'svelte';
	import Modal from '$lib/components/Modal.svelte';
	import { Button } from '$lib/components/ui';
	import { Loader2, Sparkles } from '@lucide/svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { renderTinyMarkdown } from '$lib/api/tinyMarkdown';
	import { aiInsight } from '$lib/stores/aiInsight.svelte';
	import { chartPngBase64, resultToPdfBase64 } from '$lib/email/artifacts';
	import { sendEmailSelf } from '$lib/api/emailSelf';
	import { mailHealth } from '$lib/stores/mailHealth.svelte';
	import { query } from '$lib/stores/query.svelte';
	import { generateSummary } from '$lib/email/summarize';
	import { buildCellsetDigest } from '$lib/api/cellsetDigest';
	import { aiAskHealth } from '$lib/stores/aiAskHealth.svelte';
	import type { AiCubeRef } from '$lib/api/aiAsk';

	/*
	 * "Email me this" — bundles the current AI insight (rendered to HTML),
	 * the chart PNG (chart view mode only), and a PDF snapshot of the result
	 * view, then POSTs them to the backend's send-to-self endpoint.
	 *
	 * The recipient is NOT user-chosen: the server sends only to the
	 * admin-configured address (SAIKU_MAIL_SELF_TO), surfaced read-only here via
	 * the mailHealth store. When no recipient is configured, Send is disabled.
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

	let subject = $state(untrack(() => defaultSubject()));
	let summary = $state('');
	let sending = $state(false);
	let generating = $state(false);

	function defaultSubject(): string {
		const cubeName = query.current?.cube?.caption ?? query.current?.cube?.name;
		return cubeName
			? `Saiku analysis — ${cubeName}`
			: i18n.t('modal.emailMeThis.defaultSubject', 'Saiku analysis');
	}

	$effect(() => {
		if (open) {
			untrack(() => {
				subject = defaultSubject();
				summary = aiInsight.latestMarkdown?.trim() ?? '';
			});
		}
	});

	const canGenerate = $derived(aiAskHealth.configured && !!query.result && !!query.current?.cube);

	async function handleGenerate() {
		const cube = query.current?.cube;
		if (!cube || generating) return;
		const ref: AiCubeRef = {
			connectionName: cube.connection,
			catalog: cube.catalog,
			schema: cube.schema,
			cubeName: cube.name
		};
		generating = true;
		try {
			const md = await generateSummary(ref, buildCellsetDigest(query.result) || undefined);
			if (md) summary = md;
			else
				toasts.danger(i18n.t('modal.emailMeThis.generateFailed', "Couldn't generate a summary."));
		} catch {
			toasts.danger(i18n.t('modal.emailMeThis.generateFailed', "Couldn't generate a summary."));
		} finally {
			generating = false;
		}
	}

	async function handleSend() {
		// The recipient is server-controlled; Send is disabled unless the backend
		// reports a configured self-recipient. Guard defensively regardless.
		if (!mailHealth.selfConfigured) return;
		sending = true;
		try {
			const body = {
				subject,
				summaryHtml: summary.trim() ? renderTinyMarkdown(summary) : '',
				chartPngBase64: chartPngBase64(),
				pdfBase64: await resultToPdfBase64(document.querySelector('.result-host'))
			};
			const res = await sendEmailSelf(body);
			if (res.ok) {
				toasts.success(
					i18n.t('modal.emailMeThis.sentTitle', 'Email sent'),
					mailHealth.to
						? `${i18n.t('modal.emailMeThis.sentBody', 'Sent to')} ${mailHealth.to}`
						: i18n.t('modal.emailMeThis.sentBodyGeneric', 'Your analysis is on its way.')
				);
				onClose();
				return;
			}
			if (res.status === 503) {
				toasts.danger(
					i18n.t('modal.emailMeThis.notConfiguredTitle', 'Email not configured'),
					i18n.t('modal.emailMeThis.notConfiguredBody', 'This server has no mail sender set.')
				);
				return;
			}
			if (res.status === 400) {
				const message = await extractErrorMessage(res);
				toasts.danger(
					i18n.t('modal.emailMeThis.badRequestTitle', "Couldn't send"),
					message ??
						i18n.t('modal.emailMeThis.badRequestBody', 'Check the recipient address and try again.')
				);
				return;
			}
			toasts.danger(
				i18n.t('modal.emailMeThis.failedTitle', 'Send failed'),
				i18n.t(
					'modal.emailMeThis.failedBody',
					'Something went wrong while sending. Please try again.'
				)
			);
		} catch (err) {
			toasts.danger(
				i18n.t('modal.emailMeThis.failedTitle', 'Send failed'),
				err instanceof Error
					? err.message
					: i18n.t(
							'modal.emailMeThis.failedBody',
							'Something went wrong while sending. Please try again.'
						)
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

<Modal title={i18n.t('modal.emailMeThis.title', 'Email me this')} {open} size="md" {onClose}>
	<div class="field">
		<span class="field__label">{i18n.t('modal.emailMeThis.to', 'To')}</span>
		{#if !mailHealth.selfConfigured}
			<p class="recipient recipient--muted">
				{i18n.t(
					'modal.emailMeThis.recipientNotConfigured',
					'No recipient is configured on this server.'
				)}
			</p>
		{:else if mailHealth.to}
			<p class="recipient">
				{i18n.t('modal.emailMeThis.sendsToPrefix', 'Sends to:')}
				{mailHealth.to}
			</p>
		{:else}
			<p class="recipient">
				{i18n.t(
					'modal.emailMeThis.sendsToGeneric',
					'Sends to the address configured by your administrator.'
				)}
			</p>
		{/if}
	</div>
	<label class="field">
		<span class="field__label">{i18n.t('modal.emailMeThis.subject', 'Subject')}</span>
		<input class="field__input" bind:value={subject} />
	</label>
	<label class="field">
		<span class="field__label">{i18n.t('modal.emailMeThis.summary', 'Summary')}</span>
		<textarea
			class="field__input"
			rows={4}
			bind:value={summary}
			placeholder={i18n.t(
				'modal.emailMeThis.summaryPlaceholder',
				'Add a short summary, or generate one.'
			)}></textarea>
	</label>
	{#if canGenerate}
		<Button variant="outline" size="sm" disabled={generating} onclick={handleGenerate}>
			{#if generating}
				<Loader2 size={16} class="animate-spin" />
				{i18n.t('modal.emailMeThis.generating', 'Summarizing…')}
			{:else}
				<Sparkles size={16} />
				{summary.trim()
					? i18n.t('modal.emailMeThis.regenerate', 'Regenerate')
					: i18n.t('modal.emailMeThis.generate', 'Generate summary')}
			{/if}
		</Button>
	{/if}
	{#snippet footer()}
		<Button variant="outline" onclick={onClose} disabled={sending}
			>{i18n.t('modal.cancel', 'Cancel')}</Button
		>
		<Button onclick={handleSend} disabled={sending || generating || !mailHealth.selfConfigured}>
			{#if sending}
				<Loader2 size={14} class="animate-spin" />
				{i18n.t('modal.emailMeThis.sending', 'Sending…')}
			{:else}
				{i18n.t('modal.emailMeThis.send', 'Send')}
			{/if}
		</Button>
	{/snippet}
</Modal>

<style>
	.recipient {
		margin: 0;
		padding: var(--space-2) var(--space-3);
		background: hsl(var(--bg-subtle));
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-sm);
		color: hsl(var(--fg));
		font-size: var(--fs-sm);
		word-break: break-all;
	}

	.recipient--muted {
		color: hsl(var(--fg-subtle));
	}
</style>
