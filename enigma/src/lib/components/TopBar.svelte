<script lang="ts">
	import { base } from '$app/paths';
	const pillars = ['The Web', 'Reveal', 'The Deck', 'Ask', 'Signals', 'Borderlines', 'Cases'];
	let { active = '' }: { active?: string } = $props();
</script>

<header class="bar">
	<a class="logo" href="{base}/">ENIGMA<span class="by">Benafide</span></a>
	<a class="search" href="{base}/">⌕&nbsp; Investigate a company or person…<span class="k">/</span></a>
	<nav class="nav">
		{#each pillars as p}<a class:on={p === active} href={p === 'The Deck' ? `${base}/deck` : `${base}/`}>{p}</a>{/each}
	</nav>
</header>

<style>
	.bar {
		display: flex;
		align-items: center;
		gap: 20px;
		padding: 0 20px;
		height: 56px;
		border-bottom: 1px solid var(--line);
		background: linear-gradient(180deg, #0c0e14, #090a0f);
	}
	.logo {
		font-family: var(--disp);
		font-weight: 900;
		font-size: 20px;
		letter-spacing: -0.5px;
	}
	.logo .by {
		font-family: var(--mono);
		font-size: 9px;
		color: var(--muted);
		letter-spacing: 2px;
		margin-left: 8px;
		text-transform: uppercase;
	}
	.search {
		flex: 1;
		max-width: 520px;
		display: flex;
		align-items: center;
		gap: 10px;
		background: var(--panel);
		border: 1px solid var(--line2);
		border-radius: 9px;
		padding: 9px 14px;
		color: var(--muted);
		font-size: 14px;
	}
	.search .k {
		margin-left: auto;
		font-family: var(--mono);
		font-size: 10px;
		color: var(--dim);
		border: 1px solid var(--line);
		border-radius: 4px;
		padding: 1px 6px;
	}
	.nav {
		display: flex;
		gap: 4px;
		margin-left: auto;
	}
	.nav a {
		font-size: 12px;
		color: var(--muted);
		padding: 7px 11px;
		border-radius: 7px;
	}
	.nav a.on {
		color: var(--bg);
		background: var(--amber);
		font-weight: 600;
	}
</style>
