<!--
	Select — native <select> wrapped with a hand-drawn chevron so the
	control reads flat like the rest of the design system (macOS's
	default gradient bevel doesn't match).  Three sizes matching
	Button/Input.
-->
<script lang="ts">
	// Icons are inlined SVG rather than lucide imports: this ships in a published
	// package and shouldn't force an icon-library dependency on consumers for a
	// handful of glyphs. Paths are lucide's own, each subpath anchored with an
	// absolute M (a leading relative m would resolve against the previous
	// subpath's end point and fly off the 24x24 canvas).
	import type { HTMLSelectAttributes } from 'svelte/elements';

	type Size = 'sm' | 'md' | 'lg';

	let {
		size = 'md',
		value = $bindable<string | number | null>(null),
		children,
		...rest
	}: {
		size?: Size;
		value?: string | number | null;
		children?: import('svelte').Snippet;
	} & Omit<HTMLSelectAttributes, 'size' | 'value'> = $props();

	const sizeClass = $derived(
		{
			sm: 'h-7 text-xs pl-2 pr-7',
			md: 'h-9 text-sm pl-3 pr-8',
			lg: 'h-10 text-sm pl-3 pr-8'
		}[size]
	);
	const chevronPos = $derived(size === 'sm' ? 'right-2' : 'right-2.5');
</script>

<div class="relative inline-flex w-full items-center">
	<select
		bind:value
		{...rest}
		class="w-full cursor-pointer appearance-none rounded-md border border-input bg-background text-foreground transition-colors focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/40 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50 {sizeClass}"
	>
		{@render children?.()}
	</select>
	<svg class="pointer-events-none absolute {chevronPos} size-3.5 text-muted-foreground" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
</div>
