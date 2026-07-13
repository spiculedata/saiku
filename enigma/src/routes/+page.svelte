<script lang="ts">
	import SearchBox from '$lib/components/SearchBox.svelte';
</script>

<section class="hero grid-bg">
	<div class="label">Beneficial-Ownership Intelligence</div>
	<h1 class="disp">Every company hides a <em>person</em>.<br />Enigma finds them.</h1>
	<p>Search 45.7M companies across live corporate registries — and follow the ownership up to the humans in control.</p>
	<SearchBox />
</section>

<style>
	.hero {
		min-height: calc(100vh - 56px);
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 22px;
		text-align: center;
		padding: 40px;
	}
	h1 {
		font-weight: 400;
		font-size: clamp(34px, 5vw, 60px);
		line-height: 1.05;
		letter-spacing: -1px;
	}
	h1 em {
		font-style: italic;
		color: var(--amber);
	}
	p {
		color: var(--muted);
		max-width: 56ch;
		font-size: 16px;
	}
</style>
