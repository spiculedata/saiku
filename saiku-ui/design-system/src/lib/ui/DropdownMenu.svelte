<!--
	DropdownMenu — composes Popover + a container that lays out
	MenuItems.  The caller supplies the trigger and slots menu items
	inside `menu`.  Provides sensible default width (56 = 14rem) that
	the caller can override on the container.
-->
<script lang="ts">
	import Popover from './Popover.svelte';

	// eslint-disable-next-line @typescript-eslint/no-explicit-any
	type TriggerProps = Record<string, any>;

	let {
		side = 'bottom',
		align = 'end',
		sideOffset = 6,
		open = $bindable(false),
		width = 'w-56',
		trigger,
		menu
	}: {
		side?: 'top' | 'right' | 'bottom' | 'left';
		align?: 'start' | 'center' | 'end';
		sideOffset?: number;
		open?: boolean;
		width?: string;
		trigger: import('svelte').Snippet<[{ props: TriggerProps }]>;
		menu: import('svelte').Snippet;
	} = $props();
</script>

<Popover {side} {align} {sideOffset} bind:open trigger={triggerSnippet} content={contentSnippet} />

{#snippet triggerSnippet({ props }: { props: TriggerProps })}
	{@render trigger({ props })}
{/snippet}

{#snippet contentSnippet()}
	<div role="menu" class="flex flex-col gap-0.5 p-1 {width}">
		{@render menu()}
	</div>
{/snippet}
