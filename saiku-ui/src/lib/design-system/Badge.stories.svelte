<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import { Badge } from '$lib/design-system';

	const { Story } = defineMeta({
		title: 'Compounds/Badge',
		component: Badge,
		tags: ['autodocs'],
		argTypes: {
			tone: {
				control: 'select',
				options: ['success', 'error', 'warning', 'info', 'neutral', 'primary']
			},
			shape: { control: 'radio', options: ['square', 'pill'] },
			uppercase: { control: 'boolean' }
		}
	});
</script>

<Story name="All tones (square)">
	<div class="flex flex-wrap items-center gap-2 p-8">
		<Badge tone="success">active</Badge>
		<Badge tone="error">revoked</Badge>
		<Badge tone="warning">expiring</Badge>
		<Badge tone="info">pending</Badge>
		<Badge tone="neutral">disabled</Badge>
		<Badge tone="primary">primary</Badge>
	</div>
</Story>

<Story name="Pill shape">
	<div class="flex flex-wrap items-center gap-2 p-8">
		<Badge tone="success" shape="pill">healthy</Badge>
		<Badge tone="warning" shape="pill">expiring soon</Badge>
		<Badge tone="error" shape="pill">failed</Badge>
		<Badge tone="primary" shape="pill" uppercase>tier · Business</Badge>
	</div>
</Story>

<Story name="Eyebrow style (primary + uppercase)">
	<div class="flex flex-wrap items-center gap-2 p-8">
		<Badge tone="primary" shape="pill" uppercase>Step 2 of 5</Badge>
		<Badge tone="primary" uppercase>Walkthrough</Badge>
	</div>
</Story>

<Story name="Status table (in context)">
	<div class="mx-auto max-w-md space-y-2 p-8">
		<div
			class="flex items-center justify-between rounded-md border border-border bg-card px-3 py-2"
		>
			<code class="font-mono text-xs">prod-postgres</code>
			<Badge tone="success">active</Badge>
		</div>
		<div
			class="flex items-center justify-between rounded-md border border-border bg-card px-3 py-2"
		>
			<code class="font-mono text-xs">staging-mysql</code>
			<Badge tone="warning">expiring</Badge>
		</div>
		<div
			class="flex items-center justify-between rounded-md border border-border bg-card px-3 py-2"
		>
			<code class="font-mono text-xs">legacy-snowflake</code>
			<Badge tone="neutral">revoked</Badge>
		</div>
	</div>
</Story>
