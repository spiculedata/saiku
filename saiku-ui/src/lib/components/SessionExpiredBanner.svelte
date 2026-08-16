<script lang="ts">
	import { i18n } from '$lib/stores/i18n.svelte';
	import { session } from '$lib/stores/session.svelte';
	import { hasPendingOps, notifySessionResumed } from '$lib/api/http';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { AlertTriangle, X } from '@lucide/svelte';
	import { Button } from '$lib/components/ui';

	/**
	 * Non-modal session-expired banner (issue #944).
	 *
	 * Replaces the old blocking SessionErrorModal so long-running dashboard
	 * views (TV walls / persistent-monitoring screens) aren't covered by a
	 * modal in the middle of the screen when the server session lapses. The
	 * banner pins to the top of the viewport, lets users finish reading the
	 * data underneath, and offers a non-blocking "Log in" CTA that expands
	 * into an inline credentials form.
	 *
	 * Re-auth flow is unchanged: the banner calls session.login() and then
	 * notifySessionResumed() — the http-layer pending-op replay mechanism
	 * (registerPendingOp / notifySessionResumed) is what actually preserves
	 * URL state and re-issues the failed request. Don't reinvent it.
	 */
	interface Props {
		/** Whether the banner is visible. Driven by onAuthFailure from the layout. */
		open: boolean;
		/** Tag from the original auth failure (e.g. "401" / "403") — appended for clarity. */
		statusLabel?: string;
		/** Parent callback to clear its own session-error state on dismiss / resume. */
		onDismiss: () => void;
	}

	let { open, statusLabel, onDismiss }: Props = $props();

	let expanded = $state(false);
	let username = $state('');
	let password = $state('');
	let busy = $state(false);
	let err = $state<string | null>(null);
	let usernameInput = $state<HTMLInputElement | null>(null);

	$effect(() => {
		// Reset transient state every time the banner re-opens. We DON'T touch
		// expanded here — collapsing on every re-open would be jarring if a
		// user clicked Log in and then a stale tile fetch fired a second 401.
		if (open) {
			username = session.current?.username ?? username;
			password = '';
			err = null;
		} else {
			// Once we've dismissed (or resumed) collapse the inline form back to
			// the compact banner state so the next 401 starts clean.
			expanded = false;
			password = '';
			err = null;
		}
	});

	function startLogin() {
		expanded = true;
		// Defer focus until the inputs mount.
		queueMicrotask(() => {
			usernameInput?.focus();
		});
	}

	function cancelLogin() {
		expanded = false;
		err = null;
		password = '';
	}

	async function onSubmit(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		err = null;
		try {
			await session.login(username, password);
			const hadPending = hasPendingOps();
			notifySessionResumed();
			if (hadPending) toasts.success(i18n.t('toast.resumed'), i18n.t('toast.resumed.body'));
			else toasts.success(i18n.t('toast.signedIn'));
			onDismiss();
		} catch (ex) {
			err = ex instanceof Error ? ex.message : i18n.t('login.failed', 'Sign-in failed');
		} finally {
			busy = false;
		}
	}
</script>

{#if open}
	<div class="session-banner" role="alert" aria-live="assertive">
		<div class="session-banner__row">
			<AlertTriangle size={16} class="session-banner__icon" aria-hidden="true" />
			<span class="flex-1 text-fg">
				{i18n.t('session.banner.message')}{statusLabel ? ` (${statusLabel})` : ''}.
			</span>
			<div class="flex items-center gap-2">
				{#if !expanded}
					<Button size="sm" onclick={startLogin} aria-label={i18n.t('session.banner.login')}>
						{i18n.t('session.banner.login')}
					</Button>
				{/if}
				<Button
					variant="ghost"
					size="icon"
					onclick={onDismiss}
					aria-label={i18n.t('session.banner.dismiss')}
					class="h-7 w-7"
				>
					<X size={16} />
				</Button>
			</div>
		</div>
		{#if expanded}
			<form class="session-banner__form" onsubmit={onSubmit}>
				<span class="font-bold text-fg">{i18n.t('session.banner.signInTitle')}</span>
				{#if err}
					<p class="session-banner__err" role="alert">{err}</p>
				{/if}
				<div class="session-banner__fields">
					<label class="session-banner__field">
						<span class="text-xs text-fg-muted">{i18n.t('login.username')}</span>
						<input
							bind:this={usernameInput}
							class="min-w-0 rounded-sm border border-border bg-bg px-2 py-1 text-sm text-fg"
							bind:value={username}
							autocomplete="username"
							required
							disabled={busy}
						/>
					</label>
					<label class="session-banner__field">
						<span class="text-xs text-fg-muted">{i18n.t('login.password')}</span>
						<input
							class="min-w-0 rounded-sm border border-border bg-bg px-2 py-1 text-sm text-fg"
							type="password"
							bind:value={password}
							autocomplete="current-password"
							required
							disabled={busy}
						/>
					</label>
					<div class="flex justify-end gap-2">
						<Button variant="outline" size="sm" onclick={cancelLogin} disabled={busy}>
							{i18n.t('session.banner.cancel')}
						</Button>
						<Button type="submit" size="sm" disabled={busy}>
							{busy ? i18n.t('login.submitting') : i18n.t('login.submit')}
						</Button>
					</div>
				</div>
			</form>
		{/if}
	</div>
{/if}

<style>
	/* Pinned to the top of the viewport so it doesn't displace whatever
     dashboard/tile is underneath — TV-wall layouts in particular must not
     reflow on every 401. position: fixed beats sticky here because the
     app shell uses overflow:hidden + nested flex; sticky would scroll
     out with whichever inner scroll container claims the user. */
	.session-banner {
		position: fixed;
		top: 0;
		left: 0;
		right: 0;
		z-index: 2100; /* above ToastStack (2000), below explicit modals. */
		margin: 0 auto;
		max-width: 960px;
		background: hsl(var(--bg));
		color: hsl(var(--fg));
		border: 1px solid hsl(var(--danger));
		border-top: 0;
		border-radius: 0 0 var(--radius-md) var(--radius-md);
		box-shadow: var(--shadow-md);
		font-size: var(--fs-sm);
		animation: saiku-panel-in var(--duration-normal) ease;
	}
	.session-banner__row {
		display: flex;
		align-items: center;
		gap: var(--space-3);
		padding: var(--space-2) var(--space-3);
		/* High-contrast left bar so the banner reads as urgent without
       borrowing the toast pattern (which is dismissible-info, not
       attention-required). */
		border-left: 4px solid hsl(var(--danger));
	}
	.session-banner :global(.session-banner__icon) {
		color: hsl(var(--danger));
		flex-shrink: 0;
	}
	.session-banner__form {
		display: flex;
		flex-direction: column;
		gap: var(--space-2);
		padding: var(--space-3);
		border-top: 1px solid hsl(var(--border));
		background: hsl(var(--bg-muted));
	}
	.session-banner__err {
		margin: 0;
		padding: var(--space-2);
		background: var(--danger-soft, rgba(220, 38, 38, 0.1));
		color: hsl(var(--danger));
		border-radius: var(--radius-sm);
	}
	.session-banner__fields {
		display: grid;
		grid-template-columns: 1fr 1fr auto;
		gap: var(--space-2);
		align-items: end;
	}
	.session-banner__field {
		display: flex;
		flex-direction: column;
		gap: 2px;
		min-width: 0;
	}
	@media (max-width: 640px) {
		.session-banner__fields {
			grid-template-columns: 1fr;
		}
	}
</style>
