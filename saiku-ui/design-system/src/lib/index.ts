/**
 * Design-system barrel — the package entry point, and the single import
 * surface for the compound components shared by saiku-ui and saiku-cloud.
 *
 *   import { PageHeader, FeedbackBanner, TONES, type Tone } from '@concepttocloud/saiku-design-system';
 *
 * The shadcn-shaped primitives (Button, Card, Input, Tooltip) are a
 * separate entry so a consumer can take one without the other:
 *
 *   import { Button, Card } from '@concepttocloud/saiku-design-system/ui';
 *
 * Both apps alias their historic `$lib/design-system` / `$lib/components/ui`
 * barrels onto these entries, so in-app import paths are unchanged.
 *
 * Adding a component: drop the .svelte file next to this one, re-export it
 * here, and add a story in saiku-ui (`src/lib/design-system/<Name>.stories.svelte`).
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
