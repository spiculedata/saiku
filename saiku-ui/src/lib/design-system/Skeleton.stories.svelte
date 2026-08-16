<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import { Skeleton } from '$lib/design-system';

	const { Story } = defineMeta({
		title: 'Compounds/Skeleton',
		component: Skeleton,
		tags: ['autodocs'],
		argTypes: {
			rows: { control: { type: 'number', min: 1, max: 20 } },
			variant: { control: 'radio', options: ['list', 'table'] }
		},
		parameters: {
			docs: {
				description: {
					component:
						"Async-content placeholder with shimmer. Communicates both shape and progress — preferred over plain 'Loading…' text. Respects prefers-reduced-motion."
				}
			}
		}
	});
</script>

<Story name="List">
	<div class="max-w-md">
		<Skeleton rows={5} variant="list" />
	</div>
</Story>

<Story name="Table">
	<div class="max-w-2xl">
		<Skeleton rows={4} variant="table" />
	</div>
</Story>

<Story name="Compact">
	<div class="max-w-md">
		<Skeleton rows={2} variant="list" />
	</div>
</Story>
