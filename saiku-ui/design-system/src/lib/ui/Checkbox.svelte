<!--
	Checkbox — boolean input.  Renders a real <input type="checkbox">
	visually replaced by a token-bound square.  Optional label wraps
	the input so clicks anywhere on the label toggle the box.
-->
<script lang="ts">
	// Icons are inlined SVG rather than lucide imports: this ships in a published
	// package and shouldn't force an icon-library dependency on consumers for a
	// handful of glyphs. Paths are lucide's own, each subpath anchored with an
	// absolute M (a leading relative m would resolve against the previous
	// subpath's end point and fly off the 24x24 canvas).
	import type { HTMLInputAttributes } from 'svelte/elements';

	let {
		checked = $bindable(false),
		indeterminate = false,
		disabled = false,
		label,
		description,
		...rest
	}: {
		checked?: boolean;
		indeterminate?: boolean;
		disabled?: boolean;
		label?: import('svelte').Snippet;
		description?: import('svelte').Snippet;
	} & Omit<HTMLInputAttributes, 'checked' | 'type'> = $props();
</script>

<label
	class="inline-flex cursor-pointer items-start gap-2 {disabled
		? 'pointer-events-none opacity-50'
		: ''}"
>
	<span class="relative inline-flex size-4 shrink-0 items-center justify-center">
		<input
			type="checkbox"
			bind:checked
			{disabled}
			{...rest}
			class="peer size-4 shrink-0 cursor-pointer appearance-none rounded border border-input bg-background transition-colors checked:border-primary checked:bg-primary focus-visible:ring-2 focus-visible:ring-ring/40 focus-visible:outline-none"
		/>
		{#if indeterminate}
			<svg
				class="pointer-events-none absolute size-3 text-primary-foreground"
				viewBox="0 0 24 24"
				fill="none"
				stroke="currentColor"
				stroke-width="3"
				stroke-linecap="round"
				stroke-linejoin="round"
				aria-hidden="true"><path d="M5 12h14" /></svg
			>
		{:else if checked}
			<svg
				class="pointer-events-none absolute size-3 text-primary-foreground"
				viewBox="0 0 24 24"
				fill="none"
				stroke="currentColor"
				stroke-width="3"
				stroke-linecap="round"
				stroke-linejoin="round"
				aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg
			>
		{/if}
	</span>
	{#if label || description}
		<span class="flex flex-col gap-0.5">
			{#if label}
				<span class="text-sm leading-tight text-foreground">{@render label()}</span>
			{/if}
			{#if description}
				<span class="text-xs leading-tight text-muted-foreground">{@render description()}</span>
			{/if}
		</span>
	{/if}
</label>
