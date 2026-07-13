<!--
  Primitives/Tooltip — shadcn primitive at `src/lib/components/ui/tooltip.svelte`.
  Built on bits-ui. Wraps a single child (the trigger) and surfaces `text`
  on hover/focus. Pass `text={null}` or `text=""` to disable the tooltip
  conditionally without removing the wrapper.
-->
<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import Tooltip from '$lib/components/ui/tooltip.svelte';
	import Button from '$lib/components/ui/button.svelte';
	import { Info, Trash2 } from 'lucide-svelte';

	const { Story } = defineMeta({
		title: 'Primitives/Tooltip',
		component: Tooltip,
		tags: ['autodocs'],
		argTypes: {
			text: { control: 'text' },
			delayMs: { control: 'number' },
			side: {
				control: 'select',
				options: ['top', 'right', 'bottom', 'left']
			}
		}
	});
</script>

<Story name="Default" args={{ text: 'Delete this file. Cannot be undone.', side: 'top' }}>
	{#snippet template(args: any)}
		<div class="flex items-center justify-center p-16">
			<Tooltip text={args.text} side={args.side} delayMs={args.delayMs}>
				<Button variant="ghost" size="icon" aria-label="Delete">
					<Trash2 class="h-4 w-4" />
				</Button>
			</Tooltip>
		</div>
	{/snippet}
</Story>

<Story name="On text trigger">
	<div class="flex items-center justify-center gap-2 p-16">
		<span class="text-sm">Storage cap</span>
		<Tooltip text="Files compress when they land in R2. Raw size != stored size.">
			<Info class="h-3.5 w-3.5 text-muted-foreground" />
		</Tooltip>
	</div>
</Story>

<Story name="All sides">
	<div class="mx-auto flex max-w-xl flex-wrap items-center justify-center gap-6 p-16">
		<Tooltip text="Tooltip on top" side="top">
			<Button variant="outline">Top</Button>
		</Tooltip>
		<Tooltip text="Tooltip on right" side="right">
			<Button variant="outline">Right</Button>
		</Tooltip>
		<Tooltip text="Tooltip on bottom" side="bottom">
			<Button variant="outline">Bottom</Button>
		</Tooltip>
		<Tooltip text="Tooltip on left" side="left">
			<Button variant="outline">Left</Button>
		</Tooltip>
	</div>
</Story>

<Story name="Disabled (text=null)">
	<div class="flex items-center justify-center p-16">
		<Tooltip text={null}>
			<Button variant="outline">Hover me — no tooltip</Button>
		</Tooltip>
	</div>
</Story>
