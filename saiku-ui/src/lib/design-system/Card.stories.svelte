<!--
  Primitives/Card — shadcn primitive at `src/lib/components/ui/card.svelte`
  plus its composable parts (CardHeader, CardTitle, CardDescription,
  CardContent). Use when you need a bordered surface with a header strip.
  Plain bordered cards without a header strip can use the same Card or
  a simple `rounded-lg border border-border bg-card p-4` div.
-->
<script module lang="ts">
	import { defineMeta } from '@storybook/addon-svelte-csf';
	import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '$lib/components/ui';
	import Button from '$lib/components/ui/button.svelte';

	const { Story } = defineMeta({
		title: 'Primitives/Card',
		component: Card,
		tags: ['autodocs']
	});
</script>

<Story name="Default">
	<div class="mx-auto max-w-md p-8">
		<Card>
			<CardHeader>
				<CardTitle>Connections</CardTitle>
				<CardDescription>
					Saved warehouse credentials for this tenant. Each connection points at one database.
				</CardDescription>
			</CardHeader>
			<CardContent>
				<p class="text-sm">No connections saved yet.</p>
			</CardContent>
		</Card>
	</div>
</Story>

<Story name="With CTA">
	<div class="mx-auto max-w-md p-8">
		<Card>
			<CardHeader>
				<CardTitle>Upgrade to Team</CardTitle>
				<CardDescription>
					Dedicated engine pod, SSO/SAML, audit log access. From £499/mo.
				</CardDescription>
			</CardHeader>
			<CardContent class="space-y-4">
				<p class="text-sm text-muted-foreground">
					Your trial ends in 4 days. Upgrade to keep your workspace running without interruption.
				</p>
				<Button class="w-full">Start trial</Button>
			</CardContent>
		</Card>
	</div>
</Story>
