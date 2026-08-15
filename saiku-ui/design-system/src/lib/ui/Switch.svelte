<!--
	Switch — on/off toggle.  Semantically the same as a Checkbox but
	presented as a slide affordance for high-affinity persistent
	settings (theme, feature flags, notifications).
-->
<script lang="ts">
	import type { HTMLInputAttributes } from 'svelte/elements';

	let {
		checked = $bindable(false),
		disabled = false,
		label,
		description,
		...rest
	}: {
		checked?: boolean;
		disabled?: boolean;
		label?: import('svelte').Snippet;
		description?: import('svelte').Snippet;
	} & Omit<HTMLInputAttributes, 'checked' | 'type'> = $props();
</script>

<label
	class="inline-flex cursor-pointer items-start gap-3 {disabled
		? 'pointer-events-none opacity-50'
		: ''}"
>
	<span
		class="relative inline-flex h-5 w-9 shrink-0 items-center rounded-full border border-transparent transition-colors {checked
			? 'bg-primary'
			: 'bg-muted'}"
	>
		<input
			type="checkbox"
			role="switch"
			bind:checked
			{disabled}
			{...rest}
			class="peer absolute inset-0 cursor-pointer appearance-none focus-visible:ring-2 focus-visible:ring-ring/40 focus-visible:ring-offset-1 focus-visible:outline-none"
		/>
		<span
			class="pointer-events-none absolute inline-block size-4 rounded-full bg-background shadow transition-transform {checked
				? 'translate-x-4'
				: 'translate-x-0.5'}"
			aria-hidden="true"
		></span>
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
