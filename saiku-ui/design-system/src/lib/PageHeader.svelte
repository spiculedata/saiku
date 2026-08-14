<!--
  PageHeader — the standard authed dashboard page header.

  Replaces the ~14 hand-rolled <header> blocks across the dashboard
  (eyebrow + h1 + subtitle, with an optional "← Back" link above).
  Keeps the existing visual rhythm: `space-y-2` between the eyebrow,
  h1, and subtitle, with the back link sitting in its own muted row.

  Variation supported via props + snippets:
    - title:      required headline
    - subtitle:   string OR snippet (so pages can embed <a>, <code>, etc.)
    - eyebrow:    small uppercase label above the h1 (e.g. WALKTHROUGH)
    - backHref/backLabel/backTestId: optional back link above the header
    - actions:    snippet rendered to the right of the title block
                  (e.g. "Open in Saiku Studio" CTA on /cubes)
-->
<script lang="ts">
	import type { Snippet } from 'svelte';

	interface Props {
		title: string;
		subtitle?: string | Snippet;
		eyebrow?: string;
		backHref?: string;
		backLabel?: string;
		backTestId?: string;
		actions?: Snippet;
	}

	let {
		title,
		subtitle,
		eyebrow,
		backHref,
		backLabel = '← Back',
		backTestId,
		actions
	}: Props = $props();

	const subtitleIsString = $derived(typeof subtitle === 'string');
</script>

{#if backHref}
	<a
		href={backHref}
		class="text-xs text-muted-foreground hover:text-foreground"
		data-testid={backTestId}
	>
		{backLabel}
	</a>
{/if}

<header class="flex items-start justify-between gap-4" data-testid="page-header">
	<div class="space-y-2">
		{#if eyebrow}
			<div
				class="text-xs font-medium tracking-wider text-primary uppercase"
				data-testid="page-header-eyebrow"
			>
				{eyebrow}
			</div>
		{/if}
		<h1 class="text-2xl font-semibold tracking-tight">{title}</h1>
		{#if subtitleIsString}
			<p class="max-w-3xl text-sm text-muted-foreground">{subtitle}</p>
		{:else if subtitle}
			<p class="max-w-3xl text-sm text-muted-foreground">
				{@render (subtitle as Snippet)()}
			</p>
		{/if}
	</div>
	{#if actions}
		<div class="shrink-0">
			{@render actions()}
		</div>
	{/if}
</header>
