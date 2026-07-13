<script lang="ts">
	import '../app.css';
	import TopBar from '$lib/components/TopBar.svelte';
	let { children } = $props();
</script>

<TopBar />
{@render children()}
