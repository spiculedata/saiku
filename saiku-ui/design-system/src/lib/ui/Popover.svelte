<!--
	Popover — a floating panel anchored to a trigger.  Wraps bits-ui
	Popover with the design-system's border / bg / shadow contract.
	The trigger snippet receives `props` that the caller MUST spread
	onto their button — this is the bits-ui `child` pattern, needed
	so click + aria attributes land on the actual interactive element
	(a wrapper span with `display: contents` swallows the events).

	Slot the trigger and content:

	  <Popover>
	    {#snippet trigger({ props })}
	      <button {...props}>Open</button>
	    {/snippet}
	    {#snippet content()}
	      <div class="p-3">…</div>
	    {/snippet}
	  </Popover>
-->
<script lang="ts">
	import { Popover as PopoverPrimitive } from 'bits-ui';

	// eslint-disable-next-line @typescript-eslint/no-explicit-any
	type TriggerProps = Record<string, any>;

	let {
		side = 'bottom',
		align = 'center',
		sideOffset = 6,
		open = $bindable(false),
		trigger,
		content
	}: {
		side?: 'top' | 'right' | 'bottom' | 'left';
		align?: 'start' | 'center' | 'end';
		sideOffset?: number;
		open?: boolean;
		trigger: import('svelte').Snippet<[{ props: TriggerProps }]>;
		content: import('svelte').Snippet;
	} = $props();
</script>

<PopoverPrimitive.Root bind:open>
	<PopoverPrimitive.Trigger>
		{#snippet child({ props })}
			{@render trigger({ props })}
		{/snippet}
	</PopoverPrimitive.Trigger>
	<PopoverPrimitive.Portal>
		<PopoverPrimitive.Content
			{side}
			{align}
			{sideOffset}
			class="z-50 min-w-40 rounded-md border border-border bg-popover text-popover-foreground shadow-md outline-none"
		>
			{@render content()}
		</PopoverPrimitive.Content>
	</PopoverPrimitive.Portal>
</PopoverPrimitive.Root>
