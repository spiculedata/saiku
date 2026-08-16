<!--
	Button — the action primitive.

	Renders a real <button>, or an <a> when `href` is set (same shape, so an
	anchor-as-button doesn't need hand-written classes).

	Ergonomics carried over from saiku-cloud's newer primitive set:
	  - `loading` disables the control and swaps the leading icon for a spinner
	  - `iconLeft` / `iconRight` snippets are sized and spaced by the component
	  - `fullWidth` stretches to the container
	  - `href` polymorphism

	Composability carried over from the older shadcn-shaped one:
	  - `class` merges with the variant classes (via cn / tailwind-merge), so a
	    caller can override without !important or a wrapper
	  - every other attribute spreads onto the element, which is what keeps
	    `title`, `aria-*`, `role` and `data-testid` working on ~25 call sites
	  - the class contract lives in ./button-variants and is importable on its
	    own, for code that wants the classes without the component

	The spinner is an inline SVG rather than a lucide import: this ships in a
	published package and shouldn't force an icon-library dependency on
	consumers for one glyph.
-->
<script lang="ts">
	import type { HTMLAnchorAttributes, HTMLButtonAttributes } from 'svelte/elements';
	import type { Snippet } from 'svelte';
	import { cn } from '../utils';
	import { buttonVariants, type ButtonVariant, type ButtonSize } from './button-variants';

	type Props = {
		variant?: ButtonVariant;
		size?: ButtonSize;
		loading?: boolean;
		fullWidth?: boolean;
		href?: string | null;
		children?: Snippet;
		iconLeft?: Snippet;
		iconRight?: Snippet;
		class?: string;
	} & Omit<HTMLButtonAttributes & HTMLAnchorAttributes, 'class' | 'href' | 'size'>;

	let {
		variant = 'primary',
		size = 'md',
		loading = false,
		fullWidth = false,
		href = null,
		disabled = false,
		class: className,
		children,
		iconLeft,
		iconRight,
		...restProps
	}: Props = $props();

	const classes = $derived(cn(buttonVariants({ variant, size }), fullWidth && 'w-full', className));
	// `loading` implies non-interactive: a caller shouldn't have to pass both.
	const isDisabled = $derived(disabled || loading);
</script>

{#snippet lead()}
	{#if loading}
		<svg
			class="size-3.5 animate-spin"
			viewBox="0 0 24 24"
			fill="none"
			stroke="currentColor"
			stroke-width="2"
			stroke-linecap="round"
			aria-hidden="true"
		>
			<path d="M21 12a9 9 0 1 1-6.219-8.56" />
		</svg>
	{:else}
		{@render iconLeft?.()}
	{/if}
{/snippet}

{#if href}
	<!-- eslint-disable-next-line svelte/no-navigation-without-resolve -->
	<a
		href={isDisabled ? undefined : href}
		class={classes}
		aria-disabled={isDisabled ? 'true' : undefined}
		{...restProps}
	>
		{@render lead()}
		{@render children?.()}
		{@render iconRight?.()}
	</a>
{:else}
	<button class={classes} disabled={isDisabled} {...restProps}>
		{@render lead()}
		{@render children?.()}
		{@render iconRight?.()}
	</button>
{/if}
