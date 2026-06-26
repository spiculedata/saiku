<!--
  Primitives/Button — shadcn primitive at `src/lib/components/ui/button.svelte`.
  Six variants (default, destructive, outline, secondary, ghost, link, text)
  × five sizes (default, sm, lg, icon, none). Theme toggle flips light/dark.
-->
<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import Button from '$lib/components/ui/button.svelte';
	import { Plus } from 'lucide-svelte';

	const { Story } = defineMeta({
		title: 'Primitives/Button',
		component: Button,
		tags: ['autodocs'],
		argTypes: {
			variant: {
				control: 'select',
				options: ['default', 'destructive', 'outline', 'secondary', 'ghost', 'link', 'text']
			},
			size: {
				control: 'select',
				options: ['default', 'sm', 'lg', 'icon', 'none']
			}
		}
	});
</script>

<Story name="Default (playground)" args={{ variant: 'default', size: 'default' }}>
	{#snippet template(args)}
		<div class="p-8">
			<Button {...args}>Sign in</Button>
		</div>
	{/snippet}
</Story>

<Story name="All variants">
	<div class="flex flex-wrap items-center gap-3 p-8">
		<Button variant="default">Default</Button>
		<Button variant="destructive">Destructive</Button>
		<Button variant="outline">Outline</Button>
		<Button variant="secondary">Secondary</Button>
		<Button variant="ghost">Ghost</Button>
		<Button variant="link">Link</Button>
		<Button variant="text" size="none">Text</Button>
	</div>
</Story>

<Story name="All sizes">
	<div class="flex items-center gap-3 p-8">
		<Button size="sm">Small</Button>
		<Button size="default">Default</Button>
		<Button size="lg">Large</Button>
		<Button size="icon" aria-label="Add">
			<Plus class="h-4 w-4" />
		</Button>
	</div>
</Story>

<Story name="With icon">
	<div class="flex items-center gap-3 p-8">
		<Button>
			<Plus class="h-4 w-4" />
			Add connection
		</Button>
		<Button variant="outline">
			<Plus class="h-4 w-4" />
			Add connection
		</Button>
	</div>
</Story>

<Story name="Disabled">
	<div class="flex items-center gap-3 p-8">
		<Button disabled>Default disabled</Button>
		<Button variant="outline" disabled>Outline disabled</Button>
		<Button variant="destructive" disabled>Destructive disabled</Button>
	</div>
</Story>
