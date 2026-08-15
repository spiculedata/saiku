<!--
	Input — single-line text field.

	Like Button, this is the union of the two primitive generations rather than
	one replacing the other. From the newer set: three sizes matching Button, a
	`tone="destructive"` for validation errors, and an `iconLeft` snippet for
	search fields. From the older shadcn-shaped one: `class` merging through
	tailwind-merge and full attribute spread.

	SIZING: `md` (the default) deliberately reproduces saiku-ui's legacy
	`.field__input` metrics — padding-driven height, `--border-strong`,
	`--radius-sm`, and a flat focus ring with no offset. That legacy class is on
	117 controls across 42 files while this component is on a handful, so
	matching it makes replacing `.field` a visually neutral swap instead of a
	form redesign. `sm` and `lg` are the newer set's tighter/looser steps for
	everything else.
-->
<script lang="ts" module>
	export type InputSize = 'sm' | 'md' | 'lg';
	export type InputTone = 'default' | 'destructive';
</script>

<script lang="ts">
	import type { HTMLInputAttributes } from 'svelte/elements';
	import type { Snippet } from 'svelte';
	import { cn } from '../utils';

	type Props = {
		size?: InputSize;
		tone?: InputTone;
		iconLeft?: Snippet;
		// Nullable: a raw <input> accepts null, and app state routinely models
		// "not set yet" that way. Refusing it would push `?? ''` into every
		// call site, which can't be done with bind:.
		value?: string | null;
		class?: string;
	} & Omit<HTMLInputAttributes, 'size' | 'value' | 'class'>;

	let {
		size = 'md',
		tone = 'default',
		iconLeft,
		value = $bindable<string | null>(''),
		class: className,
		...restProps
	}: Props = $props();

	const sizeClass = $derived(
		{
			sm: 'px-2.5 py-1 text-xs',
			md: 'px-3 py-2 text-md',
			lg: 'px-3.5 py-2.5 text-md'
		}[size]
	);
	const toneClass = $derived(
		tone === 'destructive'
			? 'border-destructive focus-visible:ring-destructive'
			: 'border-border-strong focus-visible:ring-ring'
	);
	// An icon needs room: pad the left edge past it rather than letting the
	// glyph sit on top of the caret.
	const iconPad = $derived(iconLeft ? 'pl-8' : '');
</script>

{#snippet field()}
	<input
		bind:value
		class={cn(
			'w-full rounded-sm border bg-background text-foreground transition-colors placeholder:text-muted-foreground focus-visible:ring-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50',
			sizeClass,
			toneClass,
			iconPad,
			className
		)}
		aria-invalid={tone === 'destructive' ? 'true' : undefined}
		{...restProps}
	/>
{/snippet}

{#if iconLeft}
	<div class="relative w-full">
		<span
			class="pointer-events-none absolute top-1/2 left-2.5 -translate-y-1/2 text-muted-foreground"
		>
			{@render iconLeft()}
		</span>
		{@render field()}
	</div>
{:else}
	{@render field()}
{/if}
