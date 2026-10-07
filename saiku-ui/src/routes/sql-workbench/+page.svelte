<script lang="ts">
	import { session } from '$lib/stores/session.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import SqlWorkbench from '$lib/views/SqlWorkbench.svelte';
	import LoginForm from '$lib/views/LoginForm.svelte';
</script>

{#if session.loading}
	<div class="m-auto text-fg-muted">{i18n.t('cubes.loading')}</div>
{:else if !session.current}
	<LoginForm />
{:else if !session.hasRole('ROLE_SQL_EXEC')}
	<div class="m-auto text-center">
		<h1>{i18n.t('sqlWorkbench.title')}</h1>
		<p>{i18n.t('sqlWorkbench.notAllowed')}</p>
	</div>
{:else}
	<SqlWorkbench />
{/if}
