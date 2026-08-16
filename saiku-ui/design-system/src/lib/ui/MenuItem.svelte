<!--
	MenuItem — a single row inside a DropdownMenu / context menu.
	Renders full-width, hover-highlights, and supports optional
	leading icon, trailing shortcut (Kbd), and destructive tone.

	Meant to be composed inside a menu container that provides the
	surface (bg-popover, border, shadow) — this snippet is just the
	interactive row.
-->
<script lang="ts">
	type Tone = 'default' | 'destructive';

	let {
		tone = 'default',
		disabled = false,
		testid,
		children,
		iconLeft,
		shortcut,
		onclick
	}: {
		tone?: Tone;
		disabled?: boolean;
		/** Optional test id — surfaced as `data-testid` on the button so
		 *  Playwright can select the row action without a class-based
		 *  probe. */
		testid?: string;
		children?: import('svelte').Snippet;
		iconLeft?: import('svelte').Snippet;
		shortcut?: import('svelte').Snippet;
		onclick?: (e: MouseEvent) => void;
	} = $props();

	const toneClass = $derived(
		tone === 'destructive'
			? 'text-destructive hover:bg-destructive/10 focus-visible:bg-destructive/10'
			: 'text-foreground hover:bg-accent focus-visible:bg-accent'
	);
</script>

<button
	type="button"
	role="menuitem"
	data-testid={testid}
	{disabled}
	{onclick}
	class="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-xs transition-colors focus-visible:outline-none disabled:pointer-events-none disabled:opacity-50 {toneClass}"
>
	{#if iconLeft}
		<span class="inline-flex size-3.5 items-center justify-center text-muted-foreground">
			{@render iconLeft()}
		</span>
	{/if}
	<span class="flex-1">{@render children?.()}</span>
	{#if shortcut}
		<span class="text-[10px] text-muted-foreground">{@render shortcut()}</span>
	{/if}
</button>
