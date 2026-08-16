<script lang="ts">
	/*
	 * Issue #933 — per-tile error state.
	 *
	 * Replaces the old raw red error text with an icon + message + a Retry
	 * button that re-runs the owning tile's query. The tile passes the error
	 * message and an `onRetry` callback (which clears its fetch-dedupe cache
	 * and re-fires the query effect).
	 */
	import { TriangleAlert, RotateCw } from '@lucide/svelte';
	import { i18n } from '$lib/stores/i18n.svelte';

	interface Props {
		message?: string | null;
		onRetry?: () => void;
	}
	let { message = null, onRetry }: Props = $props();
</script>

<div
	class="box-border flex h-full w-full flex-col items-center justify-center gap-2 p-3 text-center text-danger"
	role="alert"
>
	<TriangleAlert size={22} aria-hidden="true" />
	<p class="msg">{message ?? i18n.t('tile.error', 'Something went wrong loading this tile.')}</p>
	{#if onRetry}
		<button type="button" class="retry" onclick={onRetry}>
			<RotateCw size={14} aria-hidden="true" />
			{i18n.t('tile.retry', 'Retry')}
		</button>
	{/if}
</div>

<style>
	.msg {
		margin: 0;
		font-size: 0.8125rem;
		/* Long backend errors shouldn't blow out the tile. */
		max-width: 100%;
		overflow-wrap: anywhere;
		color: hsl(var(--danger));
	}
	.retry {
		display: inline-flex;
		align-items: center;
		gap: 0.35rem;
		padding: 0.25rem 0.6rem;
		font-size: 0.8125rem;
		color: var(--saiku-app-fg, hsl(var(--fg)));
		background: var(--saiku-app-ground, hsl(var(--bg-subtle)));
		border: 1px solid var(--saiku-app-card-border, hsl(var(--border)));
		border-radius: 4px;
		cursor: pointer;
	}
	.retry:hover {
		background: var(--saiku-app-ground, hsl(var(--bg-muted)));
	}
	.retry:focus-visible {
		outline: 2px solid hsl(var(--primary));
		outline-offset: 1px;
	}
</style>
