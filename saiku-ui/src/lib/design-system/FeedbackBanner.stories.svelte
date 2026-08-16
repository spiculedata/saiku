<!--
  FeedbackBanner stories — inline success / error / warning / info
  callout. Each tone gets its own story (so you can visually compare
  them side-by-side via the sidebar tree) plus a "size" + "with details
  snippet" variation. Theme toggle (top toolbar) flips light ↔ dark.
-->
<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import { FeedbackBanner } from '$lib/design-system';

	const { Story } = defineMeta({
		title: 'Compounds/FeedbackBanner',
		component: FeedbackBanner,
		tags: ['autodocs'],
		argTypes: {
			tone: {
				control: 'select',
				options: ['success', 'error', 'warning', 'info']
			},
			size: {
				control: 'select',
				options: ['sm', 'md']
			}
		}
	});
</script>

{#snippet TemplateWrapper(args: any, body: string)}
	<div class="mx-auto max-w-2xl px-6 py-12">
		<FeedbackBanner {...args}>{body}</FeedbackBanner>
	</div>
{/snippet}

<Story name="Success" args={{ tone: 'success', size: 'md' }}>
	{#snippet template(args)}
		{@render TemplateWrapper(args, 'Uploaded invoice.csv (1.2 MB). File id 4a9b…')}
	{/snippet}
</Story>

<Story name="Error" args={{ tone: 'error', size: 'md' }}>
	{#snippet template(args)}
		{@render TemplateWrapper(
			args,
			"Couldn't connect to the warehouse. Check the JDBC URL and credentials."
		)}
	{/snippet}
</Story>

<Story name="Warning" args={{ tone: 'warning', size: 'md' }}>
	{#snippet template(args)}
		{@render TemplateWrapper(
			args,
			'You are approaching your storage cap (430 GB of 500 GB used). Upgrade your tier for more headroom.'
		)}
	{/snippet}
</Story>

<Story name="Info" args={{ tone: 'info', size: 'md' }}>
	{#snippet template(args)}
		{@render TemplateWrapper(
			args,
			'Tip: queryable cubes on uploaded files land in the next release. Upload + AI-drafted schemas work today.'
		)}
	{/snippet}
</Story>

<Story name="Small (compact)" args={{ tone: 'success', size: 'sm' }}>
	{#snippet template(args)}
		{@render TemplateWrapper(args, 'Saved.')}
	{/snippet}
</Story>

<Story name="Error with details snippet" args={{ tone: 'error', size: 'md' }}>
	{#snippet template(args: any)}
		{@const { tone, size } = args}
		{#snippet errorKindDetails()}
			<span class="ml-2 text-xs opacity-75">(AUTH_FAILED)</span>
		{/snippet}
		<div class="mx-auto max-w-2xl px-6 py-12">
			<FeedbackBanner {tone} {size} details={errorKindDetails}>
				Could not authenticate against the warehouse.
			</FeedbackBanner>
		</div>
	{/snippet}
</Story>
