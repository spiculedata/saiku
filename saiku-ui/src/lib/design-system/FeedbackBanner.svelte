<!--
  FeedbackBanner — the inline success / error / warning / info callout
  that's currently inlined ~35 times across the dashboard.

  Tone drives the colour treatment via the design-system tokens
  (--success, --warning, --destructive, --info) so light AND dark
  mode both work without hardcoded `text-emerald-200` etc.

  Tone vocabulary and class map come from `$lib/design-system/tokens.ts`
  — a single source of truth so banners, badges, and any future
  tone-driven component all share the same Tailwind utility strings.

  Variation supported:
    - tone:    Tone (typed; 'success' | 'error' | 'warning' | 'info')
    - size:    'sm' = p-3 text-xs, 'md' = p-4 text-sm (default 'md')
    - testid:  data-testid passthrough
    - details: optional snippet rendered inline after the main message
               (used for the `(errorKind)` suffix etc.)
-->
<script lang="ts">
	import type { Snippet } from 'svelte';
	import { TONE_CLASSES, type Tone } from './tokens';

	type Size = 'sm' | 'md';

	interface Props {
		tone: Tone;
		size?: Size;
		testid?: string;
		details?: Snippet;
		children: Snippet;
	}

	let { tone, size = 'md', testid, details, children }: Props = $props();

	const toneClass = $derived(TONE_CLASSES[tone]);
	const sizeClass = $derived(size === 'sm' ? 'p-3 text-xs' : 'p-4 text-sm');
</script>

<div class="rounded-lg border {toneClass} {sizeClass}" role="status" data-testid={testid}>
	{@render children()}
	{#if details}
		{@render details()}
	{/if}
</div>
