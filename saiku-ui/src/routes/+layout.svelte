<script lang="ts">
	import { onMount } from 'svelte';
	import { Button, buttonVariants } from '$lib/components/ui';
	import { base } from '$app/paths';
	import { page } from '$app/state';
	import { session } from '$lib/stores/session.svelte';
	import { theme } from '$lib/stores/theme.svelte';
	import { platform } from '$lib/stores/platform.svelte';
	import { embed } from '$lib/stores/embed.svelte';
	import { presentation } from '$lib/stores/presentation.svelte';
	import ToastStack from '$lib/components/ToastStack.svelte';
	import UpgradeBanner from '$lib/components/UpgradeBanner.svelte';
	import { LogOut, Shield, Home, LayoutDashboard, AppWindow, UserRound } from '@lucide/svelte';
	import SessionExpiredBanner from '$lib/components/SessionExpiredBanner.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { installAuthInterceptor, onAuthFailure } from '$lib/api/http';
	// Token layer + Tailwind entry both come from the shared design-system
	// package (source at saiku-ui/design-system/), so saiku-ui and saiku-cloud
	// resolve every utility through one set of values.
	import '@concepttocloud/saiku-design-system/tokens.css';
	// Tailwind v4 entry — theme + utilities only (preflight skipped to
	// preserve the existing base CSS in app.css). Must load AFTER tokens.css
	// so the @theme bridge can reference --fg / --bg / etc.
	import '@concepttocloud/saiku-design-system/tailwind.css';
	// Motion baseline (overlay/panel keyframes + the prefers-reduced-motion
	// guard). Package-side because design-system components animate against
	// those keyframes by name.
	import '@concepttocloud/saiku-design-system/motion.css';
	import '$lib/styles/app.css';

	let { children } = $props();

	// #941 share viewer: the public /share route renders a dashboard for an
	// account-free guest — no app chrome (topbar / upgrade banner), no session.
	const isShare = $derived(page.url.pathname.startsWith(`${base}/share`));

	// Chrome-hide: `?chrome=none` renders a page full-bleed with no Saiku topbar
	// (or upgrade banner) — for embedding an App Builder app in an iframe / kiosk
	// where the host supplies its own frame. The app's own header + nav stay.
	// LATCHED: once seen, it sticks for the session — the App Builder's per-page
	// URL-state mirror rewrites `?p=…&f~…` and would otherwise drop the param.
	let bare = $state(false);
	$effect(() => {
		if (page.url.searchParams.get('chrome') === 'none') bare = true;
	});

	// App Builder routes render full-bleed by default — an app is a standalone,
	// branded experience with its OWN header + nav, so the generic Saiku topbar
	// (and upgrade banner) would be redundant chrome stacked above it. This is
	// pathname-derived (not latched) so navigating back to a dashboard restores
	// the topbar; the per-page URL mirror only rewrites the query string, never
	// the pathname, so it can't drop this.
	const isAppView = $derived(page.url.pathname.startsWith(`${base}/apps/`));

	// Non-modal session-expired banner state (issue #944). The previous
	// SessionErrorModal was a blocking modal in the middle of the screen,
	// which is jarring on long-running dashboard / TV-wall views. We now
	// pin a sticky banner to the top instead and let users sign in when
	// ready. The auth-failure detection / pending-op replay contract in
	// $lib/api/http.ts is unchanged.
	let sessionError = $state<{ open: boolean; statusLabel: string }>({
		open: false,
		statusLabel: ''
	});

	// Operator branding: try SVG, then PNG logo from <saiku.home>/branding/,
	// then the bundled default at ${base}/logo.png. Text brand is the last resort.
	let brandLogo = $state<string | null>('/ui/branding/logo.svg');
	function onBrandLogoError() {
		if (brandLogo === '/ui/branding/logo.svg') {
			brandLogo = '/ui/branding/logo.png';
		} else if (brandLogo === '/ui/branding/logo.png') {
			brandLogo = `${base}/logo.svg`;
		} else {
			brandLogo = null;
		}
	}

	// Keep a reference so $effect runs in this layout's context.
	theme;

	onMount(() => {
		embed.bootstrap();
		installAuthInterceptor();
		const unsub = onAuthFailure((status) => {
			if (session.current) {
				sessionError = { open: true, statusLabel: String(status) };
			}
		});
		const onResumed = () => {
			sessionError = { open: false, statusLabel: '' };
		};
		window.addEventListener('saiku-session-resumed', onResumed);
		session.bootstrap();
		platform.ping();
		return () => {
			unsub();
			window.removeEventListener('saiku-session-resumed', onResumed);
		};
	});

	$effect(() => {
		if (session.current && platform.version == null) {
			platform.loadVersion();
		}
		// Populate capabilities once per session so the demo-analytics gate (and any
		// other capability-driven UI) has the flag without each surface re-probing.
		if (session.current && platform.capabilities == null) {
			platform.loadCapabilities();
		}
	});
</script>

<div class="app" class:app--cursor-hidden={presentation.active && presentation.cursorHidden}>
	{#if !embed.active && !presentation.active && !isShare && !bare && !isAppView}
		<UpgradeBanner />
	{/if}
	{#if !embed.active && !presentation.active && !isShare && !bare && !isAppView}
		<header class="topbar">
			<a class="topbar__brand" href="{base}/" aria-label={i18n.t('brand')}>
				{#if brandLogo}
					<img
						class="block h-[28px] max-h-[28px] w-auto object-contain"
						src={brandLogo}
						alt={i18n.t('brand')}
						onerror={onBrandLogoError}
					/>
				{:else}
					<span class="hidden">{i18n.t('brand')}</span>
				{/if}
			</a>
			<div class="topbar__actions">
				{#if session.current}
					<span class="topbar__user" title="Signed in as {session.current.username}">
						<UserRound size={14} />
						{session.current.username}
					</span>
					{#if !page.url.pathname.startsWith(`${base}/dashboards`) && !page.url.pathname.startsWith(`${base}/apps`) && !page.url.pathname.startsWith(`${base}/admin`)}
						<a class={buttonVariants({ variant: 'outline', size: 'sm' })} href="{base}/dashboards"
							><LayoutDashboard size={14} /><span>Dashboards</span></a
						>
						<a class={buttonVariants({ variant: 'outline', size: 'sm' })} href="{base}/apps"
							><AppWindow size={14} /><span>Apps</span></a
						>
					{/if}
					{#if page.url.pathname.startsWith(`${base}/dashboards`) || page.url.pathname.startsWith(`${base}/apps`)}
						<a class={buttonVariants({ variant: 'outline', size: 'sm' })} href="{base}/"
							><Home size={14} /><span>{i18n.t('topbar.workspace')}</span></a
						>
					{/if}
					{#if session.isAdmin && !page.url.pathname.startsWith(`${base}/admin`)}
						<a class={buttonVariants({ variant: 'outline', size: 'sm' })} href="{base}/admin"
							><Shield size={14} /><span>{i18n.t('topbar.admin')}</span></a
						>
					{/if}
					{#if page.url.pathname.startsWith(`${base}/admin`)}
						<a class={buttonVariants({ variant: 'outline', size: 'sm' })} href="{base}/"
							><Home size={14} /><span>{i18n.t('topbar.workspace')}</span></a
						>
					{/if}
					<Button
						variant="outline"
						size="sm"
						data-testid="app-signout"
						onclick={() => session.logout()}
					>
						<LogOut size={14} /><span>{i18n.t('topbar.signOut')}</span>
					</Button>
				{/if}
			</div>
		</header>
	{/if}

	<main class="flex min-h-0 flex-1 overflow-hidden">
		{@render children()}
	</main>
	<ToastStack />
	<!-- Tour mount moved to the workspace route (+page.svelte) so it
       only fires where its target selectors actually exist
       (#cubes-select et al). 2026-06-08 user feedback. -->
	<SessionExpiredBanner
		open={sessionError.open}
		statusLabel={sessionError.statusLabel}
		onDismiss={() => {
			sessionError = { open: false, statusLabel: '' };
		}}
	/>
</div>

<style>
	.app {
		display: flex;
		flex-direction: column;
		height: 100vh;
		overflow: hidden;
	}
	/* Presentation mode (saiku#928): hide the cursor once the pointer has been
     idle, for a clean TV-wall surface. Applies everywhere while engaged. */
	.app--cursor-hidden,
	.app--cursor-hidden :global(*) {
		cursor: none !important;
	}
	.topbar {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--space-3);
		padding: var(--space-3) var(--space-5);
		background: hsl(var(--bg-muted));
		border-bottom: 1px solid hsl(var(--border));
	}
	.topbar__brand {
		display: flex;
		align-items: center;
		gap: var(--space-2);
		color: hsl(var(--fg));
		text-decoration: none;
	}
	.topbar__brand:hover {
		text-decoration: none;
	}
	/* Wordmark — typeset Saiku next to the symbol. Falls back to text-only
     brand when no logo file ships with the deployment. Hidden on narrow
     viewports so the topbar doesn't crowd on mobile. */
	.topbar__brand-wordmark {
		font-weight: var(--weight-bold);
		font-size: var(--fs-lg);
		letter-spacing: -0.01em;
		line-height: 1;
	}
	@media (max-width: 640px) {
	}
	.topbar__actions {
		display: flex;
		align-items: center;
		gap: var(--space-3);
	}
	.topbar__actions :global(.btn) {
		height: 32px;
		padding: 0 var(--space-3);
		line-height: 1;
		box-sizing: border-box;
	}
	.topbar__actions :global(.btn > *) {
		line-height: 1;
	}
	.topbar__actions :global(.btn svg) {
		display: block;
	}
	.topbar__actions :global(.locale select) {
		height: 100%;
		padding-top: 0;
		padding-bottom: 0;
	}
	.topbar__user {
		display: inline-flex;
		align-items: center;
		gap: var(--space-1);
		padding: 4px var(--space-2);
		background: hsl(var(--bg-subtle));
		border: 1px solid hsl(var(--border));
		border-radius: 999px;
		color: hsl(var(--fg-muted));
		font-size: var(--fs-sm);
	}
	.topbar__user :global(svg) {
		color: hsl(var(--fg-subtle));
	}
</style>
