<script lang="ts">
	import { session } from '$lib/stores/session.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import MdxWorkbench from '$lib/views/MdxWorkbench.svelte';
	import LoginForm from '$lib/views/LoginForm.svelte';
	// saiku#2618: the workbench feature is enforced at the UI layer only (it shares
	// the query API with the workspace, so there is no server path to gate) — but the
	// direct-link refusal panel still holds: a denied caller gets nowhere to run MDX
	// in the workbench surface itself.
	import FeatureDenied from '$lib/components/FeatureDenied.svelte';
</script>

{#if session.loading}
	<div class="m-auto text-fg-muted">{i18n.t('cubes.loading')}</div>
{:else if !session.current}
	<LoginForm />
{:else if !session.hasFeature('workbench')}
	<FeatureDenied featureLabel={i18n.t('topbar.workbench', 'MDX Workbench')} />
{:else}
	<MdxWorkbench />
{/if}
