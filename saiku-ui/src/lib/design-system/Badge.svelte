<!--
  Badge — small inline pill for status, tier, role, or eyebrow labels.

  Two shapes (rounded square vs rounded-full pill) × five tones (success,
  error, warning, info, neutral) + a `primary` variant for the brand-red
  eyebrow used by walkthroughs and onboarding step indicators.

  Tone resolves through the design-system tokens so light + dark both
  work without overrides. The "neutral" tone uses muted-foreground +
  muted background — the canonical "inactive / revoked / disabled" look.
-->
<script lang="ts">
	import type { Snippet } from 'svelte';

	type Tone = 'success' | 'error' | 'warning' | 'info' | 'neutral' | 'primary';
	type Shape = 'square' | 'pill';

	interface Props {
		tone?: Tone;
		shape?: Shape;
		uppercase?: boolean;
		testid?: string;
		children: Snippet;
	}

	let { tone = 'neutral', shape = 'square', uppercase = false, testid, children }: Props = $props();

	const toneClass = $derived(
		tone === 'success'
			? 'bg-success/20 text-success'
			: tone === 'error'
				? 'bg-destructive/20 text-destructive'
				: tone === 'warning'
					? 'bg-warning/20 text-warning'
					: tone === 'info'
						? 'bg-info/20 text-info'
						: tone === 'primary'
							? 'bg-primary/10 text-primary'
							: 'bg-muted text-muted-foreground'
	);
	const shapeClass = $derived(shape === 'pill' ? 'rounded-full' : 'rounded');
</script>

<span
	class="inline-flex items-center px-2.5 py-0.5 text-[11px] font-medium {shapeClass} {toneClass}"
	class:tracking-wider={uppercase}
	class:uppercase
	data-testid={testid}
>
	{@render children()}
</span>
