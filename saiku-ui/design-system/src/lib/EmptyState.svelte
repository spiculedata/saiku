<!--
  EmptyState — "No ... yet" message inside a SectionCard or as a
  standalone dashed-border block.

  Variants:
    - inline: bare <p>, sits inside an existing SectionCard body
    - card:   dashed-border block, used when there's no surrounding card
              (e.g. /walkthroughs/[slug] placeholder)

  Optional `cta` snippet renders below the message for "create one"
  / "browse docs" hand-offs.
-->
<script lang="ts">
	import type { Snippet } from 'svelte';

	type Variant = 'inline' | 'card';

	interface Props {
		message: string;
		variant?: Variant;
		testid?: string;
		cta?: Snippet;
	}

	let { message, variant = 'inline', testid, cta }: Props = $props();
</script>

{#if variant === 'card'}
	<div
		class="rounded-md border border-dashed border-border bg-card/50 p-6 text-center"
		data-testid={testid}
	>
		<p class="text-sm text-muted-foreground">{message}</p>
		{#if cta}
			<div class="mt-3">
				{@render cta()}
			</div>
		{/if}
	</div>
{:else}
	<div class="px-4 py-6 text-sm text-muted-foreground" data-testid={testid}>
		{message}
		{#if cta}
			<div class="mt-2">
				{@render cta()}
			</div>
		{/if}
	</div>
{/if}
