<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import { FormField } from '$lib/design-system';
	import { Input } from '$lib/components/ui';

	const { Story } = defineMeta({
		title: 'Compounds/FormField',
		component: FormField,
		tags: ['autodocs']
	});
</script>

<Story name="Default">
	<div class="mx-auto max-w-md p-8">
		<FormField label="Workspace slug">
			<Input placeholder="acme-analytics" />
		</FormField>
	</div>
</Story>

<Story name="With hint">
	<div class="mx-auto max-w-md p-8">
		<FormField label="JDBC URL" hint="Format: jdbc:postgresql://host:5432/database">
			<Input placeholder="jdbc:postgresql://host:5432/database" />
		</FormField>
	</div>
</Story>

<Story name="Required">
	<div class="mx-auto max-w-md p-8">
		<FormField label="Email" required>
			<Input type="email" placeholder="you@company.com" />
		</FormField>
	</div>
</Story>

<Story name="With error">
	<div class="mx-auto max-w-md p-8">
		<FormField label="JDBC URL" error="Must start with jdbc: followed by the dialect." required>
			<Input placeholder="jdbc:postgresql://…" value="postgresql://wrong-shape" />
		</FormField>
	</div>
</Story>

<Story name="Select inside FormField">
	<div class="mx-auto max-w-md p-8">
		<FormField label="Role" hint="Controls what this user can do in the tenant.">
			<select class="rounded border border-input bg-background px-3 py-2 text-sm">
				<option>member</option>
				<option>admin</option>
				<option>viewer (read-only)</option>
			</select>
		</FormField>
	</div>
</Story>
