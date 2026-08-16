<!--
	Kbd — a keyboard shortcut chip.  Renders one or more keys inside a
	monospaced pill.  Use for inline help ("press <Kbd>⌘K</Kbd>") and
	on menu-item hints ("Rename    <Kbd>R</Kbd>").
-->
<script lang="ts">
	let {
		children,
		size = 'md'
	}: {
		children?: import('svelte').Snippet;
		size?: 'sm' | 'md';
	} = $props();

	const sizeClass = $derived(size === 'sm' ? 'px-1 py-0 text-[10px]' : 'px-1.5 py-0.5 text-xs');
</script>

<kbd
	class="inline-flex items-center justify-center gap-0.5 rounded border border-border bg-muted font-mono font-medium text-foreground {sizeClass}"
>
	{@render children?.()}
</kbd>
