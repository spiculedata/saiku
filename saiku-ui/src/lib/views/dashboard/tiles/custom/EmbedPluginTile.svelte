<script lang="ts">
	/*
	 * Embed variant of the `plugin` custom tile (App Builder Phase 2, saiku#1441).
	 * Token-scoped, read-only, self-contained: renders inside the <saiku-embed/>
	 * bundle, so it uses ONLY relative imports (no `$lib` alias).
	 *
	 * SECURITY posture is IDENTICAL to the in-app PluginTile: the tile config
	 * carries ONLY a plugin id (`tile.custom.options.pluginId`); the HTML is
	 * fetched from the ADMIN registry — here via the token-scoped embed endpoint
	 * `/embed/app/{path}/plugin/{id}/html`, injected as the `fetchPluginHtml`
	 * callback by <EmbedApp>. There is NO way to supply raw HTML through tile
	 * config, closing the arbitrary-author-JS exfil hole. Same iframe
	 * sandbox="allow-scripts", same strict CSP + per-mount nonce (from
	 * pluginBridge, which is pure and imports nothing), same
	 * event.source === iframe.contentWindow + nonce message authentication.
	 *
	 * Data path: the `rows` prop is the token-scoped, RLS/PII-filtered payload
	 * <EmbedGrid> already fetched through the guarded per-tile embed query. This
	 * component adds NO second, unfiltered data fetch — it only forwards those rows
	 * into the frame. The embed surface is read-only, so `filter` requests from a
	 * plugin are ignored (there is no cross-filter bus here).
	 */

	import { onDestroy } from 'svelte';
	import {
		buildSrcdoc,
		handlePluginMessage,
		PLUGIN_MIN_H
	} from '../../../../dashboard/custom/pluginBridge';

	/** Minimal cell shape (matches the embed EmbedCell) — kept local so this
	 *  component doesn't reach into the embed module graph. */
	interface Cell {
		value: number | null;
		formatted: string;
	}
	type Row = Record<string, Cell>;

	interface Props {
		tile: { title?: string; custom?: { renderer: string; options?: Record<string, unknown> } };
		/** Token-scoped rows from <EmbedGrid>. undefined/null = still loading. */
		rows?: Row[] | null;
		/** Injected by <EmbedGrid>/<EmbedApp>: fetch an installed plugin's HTML from
		 *  the token-scoped embed endpoint. Absent (e.g. dashboard embed, which has
		 *  no app-scoped plugin endpoint) → the tile renders a placeholder, NEVER an
		 *  iframe built from client-supplied markup. */
		fetchPluginHtml?: (pluginId: string) => Promise<string>;
	}

	let { tile, rows, fetchPluginHtml }: Props = $props();

	let pluginId = $derived(
		typeof tile.custom?.options?.pluginId === 'string'
			? (tile.custom.options.pluginId as string).trim()
			: ''
	);
	let hasPlugin = $derived(pluginId.length > 0);
	let pluginOptions = $derived.by(() => {
		const opts = { ...(tile.custom?.options ?? {}) } as Record<string, unknown>;
		delete opts.pluginId;
		return opts;
	});

	// Fresh cryptographic nonce per mount (not the tile id, not Math.random).
	const nonce = crypto.randomUUID();

	// Registry HTML fetched by id via the token-scoped embed endpoint. null until
	// loaded; unavailable → placeholder (no iframe). ONLY the fetched, admin-
	// installed markup is ever wrapped into a srcdoc.
	let registryHtml = $state<string | null>(null);
	let unavailable = $state(false);

	$effect(() => {
		const id = pluginId;
		const fetcher = fetchPluginHtml;
		registryHtml = null;
		unavailable = false;
		if (!id || !fetcher) {
			if (id && !fetcher) unavailable = true;
			return;
		}
		let cancelled = false;
		fetcher(id)
			.then((html) => {
				if (cancelled) return;
				if (typeof html === 'string' && html.trim().length > 0) registryHtml = html;
				else unavailable = true;
			})
			.catch(() => {
				if (!cancelled) unavailable = true;
			});
		return () => {
			cancelled = true;
		};
	});

	let srcdoc = $derived(registryHtml ? buildSrcdoc(registryHtml, nonce) : '');

	let iframe = $state<HTMLIFrameElement | null>(null);
	let frameReady = $state(false);
	let frameHeight = $state<number | null>(null);
	let pluginError = $state<string | null>(null);

	function prefersDark(): boolean {
		return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches;
	}

	function postToFrame(type: 'init' | 'data' | 'theme', payload: unknown): void {
		const win = iframe?.contentWindow;
		if (!win) return;
		// Snapshot to a plain, structured-clone-able value — a reactive $state proxy
		// makes postMessage throw DataCloneError and strands the plugin on
		// "Waiting for data…". See PluginTile.postToFrame.
		win.postMessage({ type, nonce, payload: $state.snapshot(payload) }, '*');
	}

	// On `ready` (and on rows change) push init + the token-scoped rows + theme.
	$effect(() => {
		if (!frameReady) return;
		const data = rows ?? [];
		postToFrame('init', { options: pluginOptions });
		postToFrame('data', data);
		postToFrame('theme', { effective: prefersDark() ? 'dark' : 'light' });
	});

	function onMessage(event: MessageEvent): void {
		// Only THIS frame; nonce authenticates. event.origin is "null" — never trust it.
		if (!iframe || event.source !== iframe.contentWindow) return;
		const msg = handlePluginMessage(event.data, nonce);
		if (!msg) return;
		switch (msg.kind) {
			case 'ready':
				frameReady = true;
				break;
			case 'resize':
				frameHeight = msg.height;
				break;
			case 'error':
				pluginError = msg.message; // TEXT only, never {@html}
				break;
			case 'filter':
				// Read-only embed surface: no cross-filter bus, so drop it.
				break;
		}
	}

	$effect(() => {
		window.addEventListener('message', onMessage);
		return () => window.removeEventListener('message', onMessage);
	});

	$effect(() => {
		void srcdoc;
		frameReady = false;
	});

	onDestroy(() => {
		window.removeEventListener('message', onMessage);
	});
</script>

{#if !hasPlugin}
	<div class="state muted">No plugin configured.</div>
{:else if unavailable}
	<div class="state muted">Plugin not installed: {pluginId}</div>
{:else if !srcdoc || rows === undefined || rows === null}
	<div class="state muted">Loading…</div>
{:else}
	<div class="plugin-tile">
		<iframe
			bind:this={iframe}
			title={tile.title ?? 'Plugin tile'}
			sandbox="allow-scripts"
			referrerpolicy="no-referrer"
			{srcdoc}
			style={frameHeight === null
				? 'height:100%'
				: `height:${Math.max(PLUGIN_MIN_H, frameHeight)}px`}
		></iframe>
		{#if pluginError}
			<div class="plugin-error" role="alert">{pluginError}</div>
		{/if}
	</div>
{/if}

<style>
	.plugin-tile {
		position: relative;
		height: 100%;
		width: 100%;
		min-height: 240px;
		overflow: auto;
	}
	iframe {
		display: block;
		width: 100%;
		min-height: 40px;
		border: 0;
	}
	.plugin-error {
		position: absolute;
		left: 0;
		right: 0;
		bottom: 0;
		padding: 4px 8px;
		font-size: 12px;
		color: var(--saiku-embed-error, #b91c1c);
		background: var(--saiku-embed-tile-bg, #fff);
		border-top: 1px solid var(--saiku-embed-border, #e5e7eb);
		white-space: pre-wrap;
		word-break: break-word;
	}
	.state {
		padding: 12px;
		font-family: system-ui, sans-serif;
		font-size: 13px;
	}
	.state.muted {
		color: var(--saiku-embed-muted, #6b7280);
	}
</style>
