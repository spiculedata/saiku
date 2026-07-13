<!--
  Primitives/Input — shadcn primitive at `src/lib/components/ui/input.svelte`.
  Use for any single-line text entry. Pair with a <label class="flex
  flex-col gap-1 text-sm"><span class="font-medium">…</span><Input /></label>
  for the standard form-field shape — there's no separate Label primitive
  yet.
-->
<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import Input from '$lib/components/ui/input.svelte';

	const { Story } = defineMeta({
		title: 'Primitives/Input',
		component: Input,
		tags: ['autodocs'],
		argTypes: {
			type: {
				control: 'select',
				options: ['text', 'email', 'password', 'number', 'search', 'url']
			},
			placeholder: { control: 'text' },
			disabled: { control: 'boolean' }
		}
	});
</script>

<Story name="Default" args={{ placeholder: 'jdbc:postgresql://host:5432/database' }}>
	{#snippet template(args)}
		<div class="mx-auto max-w-md p-8">
			<Input {...args} />
		</div>
	{/snippet}
</Story>

<Story name="With label">
	<div class="mx-auto max-w-md p-8">
		<label class="flex flex-col gap-1 text-sm">
			<span class="font-medium">JDBC URL</span>
			<Input placeholder="jdbc:postgresql://host:5432/database" />
		</label>
	</div>
</Story>

<Story name="With label + hint">
	<div class="mx-auto max-w-md p-8">
		<label class="flex flex-col gap-1 text-sm">
			<span class="font-medium">Workspace slug</span>
			<Input placeholder="acme-analytics" />
			<span class="text-xs text-muted-foreground">
				Used in URLs and API calls. Lowercase, dashes between words.
			</span>
		</label>
	</div>
</Story>

<Story name="Disabled">
	<div class="mx-auto max-w-md p-8">
		<Input placeholder="Disabled" disabled />
	</div>
</Story>

<Story name="Password">
	<div class="mx-auto max-w-md p-8">
		<label class="flex flex-col gap-1 text-sm">
			<span class="font-medium">Password</span>
			<Input type="password" placeholder="••••••••" />
		</label>
	</div>
</Story>
