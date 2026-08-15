<!--
	Radio — one option in a mutually-exclusive group.  Bind `value` at
	the group level; each Radio compares its own `value` prop against
	that.  Optional label + description slot the same as Checkbox.
-->
<script lang="ts">
	import type { HTMLInputAttributes } from 'svelte/elements';

	let {
		group = $bindable(''),
		value,
		disabled = false,
		label,
		description,
		...rest
	}: {
		group?: string;
		value: string;
		disabled?: boolean;
		label?: import('svelte').Snippet;
		description?: import('svelte').Snippet;
	} & Omit<HTMLInputAttributes, 'checked' | 'type' | 'value'> = $props();

	const checked = $derived(group === value);
</script>

<label
	class="inline-flex cursor-pointer items-start gap-2 {disabled
		? 'pointer-events-none opacity-50'
		: ''}"
>
	<span class="relative inline-flex size-4 shrink-0 items-center justify-center">
		<input
			type="radio"
			bind:group
			{value}
			{disabled}
			{...rest}
			class="peer size-4 shrink-0 cursor-pointer appearance-none rounded-full border border-input bg-background transition-colors checked:border-primary focus-visible:ring-2 focus-visible:ring-ring/40 focus-visible:outline-none"
		/>
		{#if checked}
			<span class="pointer-events-none absolute size-2 rounded-full bg-primary" aria-hidden="true"
			></span>
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
