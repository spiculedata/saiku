import { tv, type VariantProps } from 'tailwind-variants';

/**
 * The button's class contract.
 *
 * Deliberately a plain .ts module, not `button.svelte`'s `<script module>`:
 * non-component code needs these class strings (the driver.js onboarding tour
 * styles its own buttons with them; anchors-as-buttons use them directly), and
 * reaching them through a component drags the whole component graph — Tooltip's
 * bits-ui included — in behind it.
 *
 * ── Two generations, unified ──────────────────────────────────────────────
 * saiku-cloud grew a second primitive set (`design-system/primitives/`) whose
 * Button had better ergonomics — loading state, icon snippets, href
 * polymorphism — but hand-rolled its classes, so it had no exportable class
 * contract and no `class` / attribute passthrough. The older shadcn-shaped one
 * had the contract and the composability but none of the ergonomics.
 *
 * This is the union: the newer visual scale and variant vocabulary, expressed
 * through tailwind-variants so the contract survives. Nothing is dropped —
 * `link` / `text` / `icon` / `none` come from the older set and are still used
 * by `buttonVariants()` callers, and `default` is kept as an alias for
 * `primary` so the ~63 call sites that relied on the old implicit default keep
 * working unchanged.
 *
 * NOTE ON SIZING: the scale here is the newer, tighter one (sm = h-7, md = h-9)
 * rather than the older (sm = h-9, default = h-10). That is a deliberate visual
 * change across the platform, not an accident.
 */
export const buttonVariants = tv({
	// Disabled treatment: explicit muted background + muted-foreground text on
	// the chromed variants. Reads as unambiguously disabled rather than "dim
	// brand colour" — the shadcn-default `disabled:opacity-50` on a saturated
	// brand red reads as a low-contrast brand button.
	//
	// Focus ring intentionally without ring-offset: saiku's chrome reads flat /
	// single-stroke, and ring-offset-2 + ring-2 produces a chunky "3D" outline
	// that fights the rest of the UI. A flat 2px ring is enough for keyboard
	// a11y. `no-underline` neutralises the global `a { text-decoration }` rule
	// for anchors styled as buttons.
	base: 'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md font-medium no-underline transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none hover:no-underline',
	variants: {
		variant: {
			primary:
				'bg-primary text-primary-foreground hover:bg-primary/90 disabled:bg-muted disabled:text-muted-foreground',
			/** Alias for `primary` — the older set's name for the same thing. */
			default:
				'bg-primary text-primary-foreground hover:bg-primary/90 disabled:bg-muted disabled:text-muted-foreground',
			secondary:
				'bg-secondary text-secondary-foreground hover:bg-secondary/80 disabled:bg-muted disabled:text-muted-foreground',
			outline:
				'border border-input bg-background text-foreground hover:bg-accent hover:text-accent-foreground disabled:bg-muted disabled:text-muted-foreground disabled:border-transparent',
			ghost:
				'bg-transparent text-foreground hover:bg-accent hover:text-accent-foreground disabled:text-muted-foreground',
			destructive:
				'bg-destructive text-destructive-foreground hover:bg-destructive/90 disabled:bg-muted disabled:text-muted-foreground',
			link: 'text-primary underline-offset-4 hover:underline disabled:text-muted-foreground disabled:no-underline',
			// "text" — looks like plain text. Foreground colour, no background,
			// no border, no padding. Underlines only on hover or keyboard focus.
			// Pair with `size="none"` to fully strip height/padding. Use when a
			// click action should visually read as text (e.g. "Skip for now"
			// alongside a primary CTA).
			text: 'appearance-none bg-transparent text-foreground shadow-none hover:underline focus-visible:underline focus-visible:ring-0 focus-visible:ring-offset-0 disabled:text-muted-foreground'
		},
		size: {
			sm: 'h-7 gap-1.5 px-2.5 text-xs',
			md: 'h-9 px-3 text-sm',
			/** Alias for `md` — the older set's name for the same slot. */
			default: 'h-9 px-3 text-sm',
			lg: 'h-10 px-4 text-sm',
			/** Square, for a lone icon with no label. */
			icon: 'h-9 w-9 text-sm',
			/** No height, no padding, no radius — for `text` variant or inline flow. */
			none: 'h-auto p-0 rounded-none'
		}
	},
	defaultVariants: {
		variant: 'primary',
		size: 'md'
	}
});

export type ButtonVariant = VariantProps<typeof buttonVariants>['variant'];
export type ButtonSize = VariantProps<typeof buttonVariants>['size'];
