<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import { Toast } from '$lib/design-system';

	const { Story } = defineMeta({
		title: 'Compounds/Toast',
		component: Toast,
		tags: ['autodocs'],
		argTypes: {
			tone: { control: 'select', options: ['success', 'error', 'warning', 'info'] }
		}
	});
</script>

<Story name="Success">
	<div class="relative h-64">
		<Toast tone="success" onDismiss={() => {}}>Uploaded invoice.csv successfully</Toast>
	</div>
</Story>

<Story name="Error">
	<div class="relative h-64">
		<Toast tone="error" onDismiss={() => {}}>Could not save — try again in a moment.</Toast>
	</div>
</Story>

<Story name="Warning">
	<div class="relative h-64">
		<Toast tone="warning" onDismiss={() => {}}>
			You're approaching your storage cap (430 GB of 500 GB).
		</Toast>
	</div>
</Story>

<Story name="Without dismiss button">
	<div class="relative h-64">
		<Toast tone="info">Background job started — refresh in a moment.</Toast>
	</div>
</Story>
