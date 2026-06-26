<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import SectionCard from './SectionCard.svelte';
	import EmptyState from './EmptyState.svelte';

	const { Story } = defineMeta({
		title: 'Compounds/SectionCard',
		component: SectionCard,
		tags: ['autodocs']
	});
</script>

<Story name="With title + count">
	<div class="mx-auto max-w-2xl p-8">
		<SectionCard title="Existing keys" count={3}>
			<div class="px-4 py-3 text-sm">
				<div class="border-t border-border/40 py-2">api-key-1</div>
				<div class="border-t border-border/40 py-2">api-key-2</div>
				<div class="border-t border-border/40 py-2">api-key-3</div>
			</div>
		</SectionCard>
	</div>
</Story>

<Story name="Empty">
	<div class="mx-auto max-w-2xl p-8">
		<SectionCard title="Members" count={0}>
			<EmptyState message="No members listed." variant="inline" />
		</SectionCard>
	</div>
</Story>

<Story name="With header actions">
	{#snippet template()}
		<div class="mx-auto max-w-2xl p-8">
			{#snippet actions()}
				<button type="button" class="text-xs text-primary underline-offset-4 hover:underline">
					Download CSV ↓
				</button>
			{/snippet}
			<SectionCard title="Audit events" count={42} {actions}>
				<div class="px-4 py-3 text-sm text-muted-foreground">… events table here …</div>
			</SectionCard>
		</div>
	{/snippet}
</Story>
