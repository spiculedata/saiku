<script lang="ts">
	/*
	 * Skeleton placeholder for async list/table content.
	 *
	 * Prefer over a plain "Loading…" — the shimmer rows communicate
	 * shape (what's about to render) and progress (something is
	 * happening) at the same time. Use `rows` to roughly match the
	 * eventual content density.
	 *
	 * Respects prefers-reduced-motion: the shimmer animation is
	 * suppressed and the bars render at a flat resting opacity.
	 */

	interface Props {
		rows?: number;
		/** Variant tuning the bar layout for the surface it sits in. */
		variant?: 'list' | 'table';
	}

	let { rows = 5, variant = 'list' }: Props = $props();

	// Map list/table rows to distinct Tailwind class strings so PurgeCSS
	// catches both at build time. $derived so the variant prop reactively
	// re-resolves if the parent re-renders with a new value.
	const rowClass = $derived(
		variant === 'list'
			? 'flex flex-col gap-1.5 border-b border-border px-3 py-2 last:border-b-0'
			: 'grid grid-cols-[2fr_1fr_1fr] gap-3 border-b border-border p-3'
	);
</script>

<div class="flex flex-col gap-2 py-3" aria-busy="true" aria-live="polite">
	{#each Array(rows) as _, i (i)}
		<div class={rowClass}>
			{#if variant === 'list'}
				<div class="skeleton-bar h-3 w-[60%]"></div>
				<div class="skeleton-bar h-[9px] w-[35%]"></div>
			{:else}
				<div class="skeleton-bar h-[10px] w-[80%]"></div>
				<div class="skeleton-bar h-[10px] w-[80%]"></div>
				<div class="skeleton-bar h-[10px] w-[80%]"></div>
			{/if}
		</div>
	{/each}
</div>

<style>
	/* Shimmer gradient + keyframe stay vanilla CSS — Tailwind 4's
     arbitrary-animation escape (`animate-[…]`) can't define keyframes
     inline, and a single shared `@keyframes` doesn't compose into
     utility classes. The colour stops are the bridged tokens so light
     + dark mode still flip correctly. */
	.skeleton-bar {
		border-radius: var(--radius-sm);
		background: linear-gradient(
			90deg,
			hsl(var(--bg-muted)) 0%,
			hsl(var(--bg-subtle)) 50%,
			hsl(var(--bg-muted)) 100%
		);
		background-size: 200% 100%;
		animation: skeleton-shimmer 1.4s ease-in-out infinite;
	}

	@keyframes skeleton-shimmer {
		0% {
			background-position: 200% 0;
		}
		100% {
			background-position: -200% 0;
		}
	}

	@media (prefers-reduced-motion: reduce) {
		.skeleton-bar {
			animation: none;
			background: hsl(var(--bg-subtle));
		}
	}
</style>
