/**
 * Design-system barrel — the single import surface for everything in
 * the dashboard's design system layer.
 *
 * Consumers should reach for this barrel rather than deep paths:
 *
 *   import { PageHeader, FeedbackBanner, TONES, type Tone } from '$lib/design-system';
 *
 * Adding a new design-system component:
 * 1. Drop the file under `src/lib/components/<bucket>/<Name>.svelte`.
 * 2. Re-export it here.
 * 3. Document it in `docs/design-system.md` with its props.
 *
 * Why a barrel instead of moving files: keeps file paths stable across
 * the codebase (no broken Playwright tests, no import churn) while
 * giving the design system a real identity at the module level. If we
 * ever publish this as a separate package, this barrel is the entry.
 */

export { default as PageHeader } from './PageHeader.svelte';
export { default as FeedbackBanner } from './FeedbackBanner.svelte';
export { default as Badge } from './Badge.svelte';
export { default as EmptyState } from './EmptyState.svelte';
export { default as SectionCard } from './SectionCard.svelte';
export { default as Toast } from './Toast.svelte';
export { default as FormField } from './FormField.svelte';
export { default as SortableColumnHeader } from './SortableColumnHeader.svelte';

// Tokens — typed contract for tone / size vocabulary.
export { TONES, TONE_CLASSES, SIZES, type Tone, type Size } from './tokens';
