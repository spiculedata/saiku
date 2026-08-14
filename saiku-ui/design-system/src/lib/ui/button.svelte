<script lang="ts" module>
	// The class contract lives in a plain .ts module so non-component code can
	// import it without pulling the component graph in behind it — see the note
	// in ./button-variants.ts. Re-exported here so `import { buttonVariants }
	// from '.../ui'` keeps working.
	export { buttonVariants, type ButtonVariant, type ButtonSize } from './button-variants';
</script>

<script lang="ts">
	import type { HTMLButtonAttributes } from 'svelte/elements';
	import { cn } from '../utils';
	// The module-block re-export above doesn't bind these locally — import them
	// for use in the props type and the class call below.
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
