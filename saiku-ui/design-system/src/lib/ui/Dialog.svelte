<!--
	Dialog — modal shell.  Wraps bits-ui Dialog with the design-system
	surface (border, bg-popover, shadow) and a centered layout.  Slots
	for title + description + body + footer.  Close button lives in
	the top-right; Escape + backdrop click also close.

	Common shape:

	  <Dialog bind:open title="Remove FoodMart?">
	    {#snippet description()}
	      This permanently removes v3 and its 2 earlier versions.
	    {/snippet}
	    {#snippet footer()}
	      <Button variant="outline">Cancel</Button>
	      <Button variant="destructive">Remove</Button>
	    {/snippet}
	  </Dialog>
-->
<script lang="ts">
	import { Dialog as DialogPrimitive } from 'bits-ui';
	// Icons are inlined SVG rather than lucide imports: this ships in a published
	// package and shouldn't force an icon-library dependency on consumers for a
	// handful of glyphs. Paths are lucide's own, each subpath anchored with an
	// absolute M (a leading relative m would resolve against the previous
	// subpath's end point and fly off the 24x24 canvas).

	let {
		open = $bindable(false),
		title,
		size = 'md',
		description,
		body,
		footer
	}: {
		open?: boolean;
		title?: string;
		size?: 'sm' | 'md' | 'lg' | 'xl';
		description?: import('svelte').Snippet;
		body?: import('svelte').Snippet;
		footer?: import('svelte').Snippet;
	} = $props();

	const widthClass = $derived(
		{
			sm: 'max-w-sm',
			md: 'max-w-md',
			lg: 'max-w-lg',
			xl: 'max-w-2xl'
		}[size]
	);
</script>

<DialogPrimitive.Root bind:open>
	<DialogPrimitive.Portal>
		<DialogPrimitive.Overlay class="fixed inset-0 z-50 bg-background/70 backdrop-blur-sm" />
		<DialogPrimitive.Content
			class="fixed top-1/2 left-1/2 z-50 w-[90vw] {widthClass} -translate-x-1/2 -translate-y-1/2 rounded-lg border border-border bg-popover text-popover-foreground shadow-lg outline-none"
		>
			<header class="flex items-start justify-between gap-3 border-b border-border px-5 py-4">
				<div class="min-w-0 flex-1">
					{#if title}
						<DialogPrimitive.Title class="text-base font-semibold">
							{title}
						</DialogPrimitive.Title>
					{/if}
					{#if description}
						<DialogPrimitive.Description class="mt-1 text-xs leading-relaxed text-muted-foreground">
							{@render description()}
						</DialogPrimitive.Description>
					{/if}
				</div>
				<!-- Close button — icon + label so the affordance reads
				     without hover-scrubbing. -->
				<DialogPrimitive.Close
					class="inline-flex h-6 shrink-0 items-center gap-1 rounded px-1.5 text-[11px] font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
					aria-label="Close dialog"
				>
					Close
					<svg class="size-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 6 6 18M6 6l12 12" /></svg>
				</DialogPrimitive.Close>
			</header>
			{#if body}
				<div class="px-5 py-4 text-sm">
					{@render body()}
				</div>
			{/if}
			{#if footer}
				<footer class="flex items-center justify-end gap-2 border-t border-border px-5 py-3">
					{@render footer()}
				</footer>
			{/if}
		</DialogPrimitive.Content>
	</DialogPrimitive.Portal>
</DialogPrimitive.Root>
