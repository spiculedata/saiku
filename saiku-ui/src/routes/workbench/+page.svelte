<script lang="ts">
	import { session } from '$lib/stores/session.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import MdxWorkbench from '$lib/views/MdxWorkbench.svelte';
	import LoginForm from '$lib/views/LoginForm.svelte';
</script>

{#if session.loading}
	<div class="m-auto text-fg-muted">{i18n.t('cubes.loading')}</div>
{:else if !session.current}
	<LoginForm />
{:else}
	<MdxWorkbench />
{/if}
