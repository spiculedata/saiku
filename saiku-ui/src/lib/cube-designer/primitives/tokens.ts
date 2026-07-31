/**
 * Design-system token contract — TypeScript source of truth.
 *
 * Components consume these types (e.g. `FeedbackBanner` props.tone) so
 * `tone: 'sucess'` is a compile-time error, not a runtime mystery. The
 * underlying CSS variables (`--success`, `--success-foreground`, etc.)
 * live in `src/routes/layout.css` and resolve to a light + dark value
 * each — see `docs/design-system.md` for the full token map.
 *
 * Why types here instead of free strings inline in each component:
 * - One place to add a new tone (e.g. 'neutral') instead of grep-and-replace.
 * - Misuse becomes a compile error visible in CI, not a visual bug.
 * - Auto-complete in editors surfaces the available options.
 */

/** Status tones for feedback surfaces (banners, badges, toasts). */
export const TONES = ["success", "error", "warning", "info"] as const;
export type Tone = (typeof TONES)[number];

/** Size scale for compact / standard / generous variants of compound
 *  components (banners, cards, etc.). Buttons use their own scale via
 *  the `Button` primitive — see `src/lib/components/ui/button.svelte`. */
export const SIZES = ["sm", "md", "lg"] as const;
export type Size = (typeof SIZES)[number];

/**
 * Tailwind class map per tone. Centralised so banners, badges, and any
 * future tone-driven component all reach for the same utility strings
 * — no copy-pasted `border-success/40 bg-success/10 text-success` drift
 * across consumers. Keys match the `Tone` union; values are the trio of
 * (border, background-wash, foreground) classes that work in both
 * light AND dark mode because they resolve to `hsl(var(--<tone>))`.
 */
export const TONE_CLASSES: Record<Tone, string> = {
  success: "border-success/40 bg-success/10 text-success",
  error: "border-destructive/40 bg-destructive/10 text-destructive",
  warning: "border-warning/40 bg-warning/10 text-warning",
  info: "border-info/40 bg-info/10 text-info",
};
