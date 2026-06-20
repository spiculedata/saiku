<script lang="ts" module>
	import { tv, type VariantProps } from 'tailwind-variants';

	export const buttonVariants = tv({
		// Disabled treatment: explicit muted background + muted-foreground
		// text on the chromed variants (default / destructive / outline /
		// secondary). Reads as unambiguously disabled, not "dim brand
		// colour". For chromeless variants (ghost / link / text) the bg
		// stays absent and only the foreground mutes. Replaces the
		// shadcn-default `disabled:opacity-50`, which on saturated brand
		// reds read as low-contrast brand button.
		base: 'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium ring-offset-background transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none',
		variants: {
			variant: {
				default:
					'bg-primary text-primary-foreground hover:bg-primary/90 disabled:bg-muted disabled:text-muted-foreground',
				destructive:
					'bg-destructive text-destructive-foreground hover:bg-destructive/90 disabled:bg-muted disabled:text-muted-foreground',
				outline:
					'border border-input bg-background hover:bg-accent hover:text-accent-foreground disabled:bg-muted disabled:text-muted-foreground disabled:border-transparent',
				secondary:
					'bg-secondary text-secondary-foreground hover:bg-secondary/80 disabled:bg-muted disabled:text-muted-foreground',
				ghost: 'hover:bg-accent hover:text-accent-foreground disabled:text-muted-foreground',
				link: 'text-primary underline-offset-4 hover:underline disabled:text-muted-foreground disabled:no-underline',
				// "text" — looks like plain text. Foreground color, no background,
				// no border, no padding. Underlines only on hover or keyboard focus.
				// Inherits font-medium from base; pass `class="font-bold"` to override.
				// Pair with `size="none"` to fully strip height/padding. Use this when
				// you want a click action that visually reads as text (e.g. "Skip for
				// now" alongside a primary CTA).
				text: 'appearance-none bg-transparent text-foreground shadow-none hover:underline focus-visible:underline focus-visible:ring-0 focus-visible:ring-offset-0 disabled:text-muted-foreground'
			},
			size: {
				default: 'h-10 px-4 py-2',
				sm: 'h-9 rounded-md px-3',
				lg: 'h-11 rounded-md px-8',
				icon: 'h-10 w-10',
				// "none" — no height, no padding, no border-radius. For text variant
				// or when you need a button that flows inline with surrounding text.
				none: 'h-auto p-0 rounded-none'
			}
		},
		defaultVariants: {
			variant: 'default',
			size: 'default'
		}
	});

	export type ButtonVariant = VariantProps<typeof buttonVariants>['variant'];
	export type ButtonSize = VariantProps<typeof buttonVariants>['size'];
</script>

<script lang="ts">
	import type { HTMLButtonAttributes } from 'svelte/elements';
	import { cn } from '$lib/utils';

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
