<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import EmptyState from './EmptyState.svelte';

	const { Story } = defineMeta({
		title: 'Compounds/EmptyState',
		component: EmptyState,
		tags: ['autodocs'],
		argTypes: {
			message: { control: 'text' },
			variant: { control: 'radio', options: ['inline', 'card'] }
		}
	});
</script>

<Story
	name="Inline (inside SectionCard)"
	args={{ message: 'No keys yet — create one above.', variant: 'inline' }}
>
	{#snippet template(args: any)}
		<div class="mx-auto max-w-md p-8">
			<div class="rounded-lg border border-border bg-card">
				<header class="border-b border-border px-4 py-3 text-sm font-medium">
					Existing keys (0)
				</header>
				<EmptyState {...args} />
			</div>
		</div>
	{/snippet}
</Story>

<Story
	name="Card (standalone)"
	args={{
		message: 'No connections saved yet — pick a warehouse above to add one.',
		variant: 'card'
	}}
>
	{#snippet template(args: any)}
		<div class="mx-auto max-w-md p-8">
			<EmptyState {...args} />
		</div>
	{/snippet}
</Story>

<Story name="With CTA">
	{#snippet template()}
		<div class="mx-auto max-w-md p-8">
			{#snippet cta()}
				<a href="/cubes/new" class="text-sm text-primary underline-offset-4 hover:underline">
					Create a cube →
				</a>
			{/snippet}
			<EmptyState
				message="No cubes yet — Saiku queries through cubes, so you need at least one."
				variant="card"
				{cta}
			/>
		</div>
	{/snippet}
</Story>
