<!--
  SectionCard — bordered card surface with an optional header strip.

  The header strip is what distinguishes this from a plain bordered
  card (which shadcn `Card` already handles). Used for "Existing keys
  (3)" / "Members (12)" / "Uploaded files (4)" patterns where you
  want a labeled, count-bearing region of the page.

  Variation:
    - title:    string headline shown in the header strip
    - count:    optional integer rendered as `Title (N)`
    - actions:  optional right-aligned slot in the header (e.g. a
                CSV download link on the audit page)
    - children: the card body
-->
<script lang="ts">
	import type { Snippet } from 'svelte';

	interface Props {
		title?: string;
		count?: number;
		actions?: Snippet;
		testid?: string;
		children: Snippet;
	}

	let { title, count, actions, testid, children }: Props = $props();
</script>

<section class="rounded-lg border border-border bg-card" data-testid={testid}>
	{#if title || actions}
		<header
			class="flex items-center justify-between gap-3 border-b border-border px-4 py-3 text-sm font-medium"
		>
			<span>
				{title}{#if count !== undefined}
					&nbsp;({count}){/if}
			</span>
			{#if actions}
				<span class="shrink-0">{@render actions()}</span>
			{/if}
		</header>
	{/if}
	{@render children()}
</section>
