<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import LocalePicker from '$lib/components/LocalePicker.svelte';

	const { Story } = defineMeta({
		title: 'Compounds/LocalePicker',
		component: LocalePicker,
		tags: ['autodocs'],
		parameters: {
			docs: {
				description: {
					component:
						'Language switcher — read/writes the global $lib/stores/i18n locale. Lives in the topbar and any settings surface that needs a language picker.'
				}
			}
		}
	});
</script>

<Story name="Default">
	<div class="flex items-center gap-3 p-4">
		<span class="text-sm text-fg-muted">Pick a language:</span>
		<LocalePicker />
	</div>
</Story>
