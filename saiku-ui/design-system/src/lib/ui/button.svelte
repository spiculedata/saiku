<script lang="ts">
	import type { HTMLButtonAttributes } from 'svelte/elements';
	import { cn } from '../utils';
	// The class contract lives in a plain .ts module so non-component code can
	// import it without pulling the component graph in behind it — see the note
	// in ./button-variants.ts.
	//
	// This component does NOT re-export it. The `/ui` barrel sources the variants
	// straight from ./button-variants, and the package's exports map never exposes
	// button.svelte on its own, so nothing can reach the variants through here.
	// (Re-exporting an imported binding from a Svelte <script module> also trips
	// eslint's no-import-assign, since svelte-eslint-parser shares one scope
	// across the two script blocks.)
	import { buttonVariants, type ButtonVariant, type ButtonSize } from './button-variants';

	interface Props extends HTMLButtonAttributes {
		variant?: ButtonVariant;
		size?: ButtonSize;
	}

	let {
		variant = 'default',
		size = 'default',
		class: className,
		children,
		...restProps
	}: Props = $props();
</script>

<button class={cn(buttonVariants({ variant, size }), className)} {...restProps}>
	{@render children?.()}
</button>
