<!--
  PageHeader stories — the standard authed dashboard page header.
  Covers every visible prop variation so designers + devs can see the
  shape independently of any route. Theme toggle (top toolbar) flips
  light ↔ dark via the global `data-theme` decorator.
-->
<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import PageHeader from './PageHeader.svelte';

	const { Story } = defineMeta({
		title: 'Compounds/PageHeader',
		component: PageHeader,
		tags: ['autodocs'],
		argTypes: {
			title: { control: 'text' },
			subtitle: { control: 'text' },
			eyebrow: { control: 'text' },
			backHref: { control: 'text' },
			backLabel: { control: 'text' }
		}
	});
</script>

<!-- Pad the story canvas so the header doesn't kiss the viewport edge,
     matching the `max-w-5xl ... px-6 py-12` shape every dashboard
     page uses around its <main>. -->
{#snippet TemplateWrapper(args: any)}
	<div class="mx-auto flex max-w-5xl flex-col gap-6 px-6 py-12">
		<PageHeader {...args} />
	</div>
{/snippet}

<Story
	name="Default"
	args={{
		title: 'API keys',
		subtitle:
			'Create, list, and revoke api-keys for your tenant. Each key auths a Bearer Authorization on api.saiku.bi calls.'
	}}
>
	{#snippet template(args)}
		{@render TemplateWrapper(args)}
	{/snippet}
</Story>

<Story
	name="With back link"
	args={{
		title: 'Workspaces',
		subtitle: 'Single workspace per tenant in v1. Multi-workspace support lands in M7-12.',
		backHref: '/',
		backLabel: '← Home'
	}}
>
	{#snippet template(args)}
		{@render TemplateWrapper(args)}
	{/snippet}
</Story>

<Story
	name="With eyebrow"
	args={{
		eyebrow: 'WALKTHROUGH',
		title: 'Connect your data',
		subtitle:
			'How to bring datasets into Saiku from your warehouses, file uploads, or existing connections.'
	}}
>
	{#snippet template(args)}
		{@render TemplateWrapper(args)}
	{/snippet}
</Story>

<Story
	name="Title only"
	args={{
		title: 'Account'
	}}
>
	{#snippet template(args)}
		{@render TemplateWrapper(args)}
	{/snippet}
</Story>
