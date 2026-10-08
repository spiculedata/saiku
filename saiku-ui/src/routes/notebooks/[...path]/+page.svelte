<script lang="ts">
	/*
	 * Notebooks route (issue #1108). Gates on the session store the same way
	 * the dashboards route does — unauthenticated visitors get LoginForm, not
	 * a broken-fetch editor full of "NetworkError" messages.
	 */

	import { session } from '$lib/stores/session.svelte';
	import LoginForm from '$lib/views/LoginForm.svelte';
	import NotebookEditor from '$lib/views/notebook/NotebookEditor.svelte';
	import NotebookIndex from '$lib/views/notebook/NotebookIndex.svelte';

	let { data } = $props();
</script>

{#if session.loading}
	<div class="m-auto text-fg-muted">Loading…</div>
{:else if session.current}
	{#if !data.notebookPath}
		<NotebookIndex />
	{:else}
		<NotebookEditor notebookPath={data.notebookPath} />
	{/if}
{:else}
	<LoginForm />
{/if}
