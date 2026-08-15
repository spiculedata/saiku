<!--
	Textarea — multi-line input.  Same border / focus / tone contract
	as Input.  `rows` sets the initial visible height; users can still
	drag-resize if the CSS allows.
-->
<script lang="ts">
	import type { HTMLTextareaAttributes } from 'svelte/elements';

	type Tone = 'default' | 'destructive';

	let {
		tone = 'default',
		value = $bindable(''),
		rows = 4,
		...rest
	}: {
		tone?: Tone;
		value?: string;
		rows?: number;
	} & Omit<HTMLTextareaAttributes, 'value'> = $props();

	const toneClass = $derived(
		tone === 'destructive'
			? 'border-destructive focus-visible:ring-destructive/40'
			: 'border-input focus-visible:ring-ring/40 focus-visible:border-ring'
	);
</script>

<textarea
	bind:value
	{rows}
	{...rest}
	class="w-full resize-y rounded-md border bg-background px-3 py-2 text-sm text-foreground transition-colors placeholder:text-muted-foreground focus-visible:ring-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50 {toneClass}"
></textarea>
