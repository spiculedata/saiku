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
		// Deliberately undefined, not null. `bind:value` with an explicit null
		// makes the browser look for an <option> whose value is null; none
		// matches, so the control renders BLANK instead of falling back to the
		// first option. Undefined lets Svelte adopt the first option's value on
		// mount, which is what a native <select> does.
		value = $bindable<string | number | undefined>(undefined),
		children,
		...rest
	}: {
		size?: Size;
		value?: string | number | undefined;
		children?: import('svelte').Snippet;
	} & Omit<HTMLSelectAttributes, 'size' | 'value'> = $props();

	const sizeClass = $derived(
		{
			sm: 'py-1 text-xs pr-7 pl-2.5',
			// md reproduces the legacy .field__input box: padding-driven height,
			// 14px text. A fixed h-9 made selects a different height from the
			// inputs beside them in the same form.
			md: 'py-2 text-md pr-8 pl-3',
			lg: 'py-2.5 text-md pr-8 pl-3.5'
		}[size]
	);
	const chevronPos = $derived(size === 'sm' ? 'right-2' : 'right-2.5');
</script>

<div class="relative inline-flex w-full items-center">
	<select
		bind:value
		{...rest}
		class="w-full cursor-pointer appearance-none rounded-sm border border-border-strong bg-background text-foreground transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50 {sizeClass}"
	>
		{@render children?.()}
	</select>
	<svg
		class="pointer-events-none absolute {chevronPos} size-3.5 text-muted-foreground"
		viewBox="0 0 24 24"
		fill="none"
		stroke="currentColor"
		stroke-width="2"
		stroke-linecap="round"
		stroke-linejoin="round"
		aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg
	>
</div>
