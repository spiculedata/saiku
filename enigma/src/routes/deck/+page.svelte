<script lang="ts">
	import DeckChart from '$lib/components/DeckChart.svelte';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();
</script>

<div class="deck grid-bg">
	<header class="head">
		<div class="label">Analytics · live over the Ossie model</div>
		<h1 class="disp">The Deck</h1>
		<p class="sub">
			Saiku aggregating DuckDB via the semantic model — every chart below is a live query, not a snapshot.
		</p>
	</header>

	<section class="grid">
		<article class="card">
			<div class="card-head">
				<h2 class="disp">Ownership links by jurisdiction</h2>
				<div class="label">Top 10 · ownership count</div>
			</div>
			<DeckChart type="bar" data={data.jurisdiction} />
		</article>

		<article class="card">
			<div class="card-head">
				<h2 class="disp">Company status</h2>
				<div class="label">Top 8 · company count</div>
			</div>
			<DeckChart type="donut" data={data.status} />
		</article>

		<article class="card wide">
			<div class="card-head">
				<h2 class="disp">How control is held</h2>
				<div class="label">Top 10 interest types · ownership count</div>
			</div>
			<DeckChart type="hbar" data={data.interestType} color="#f5b544" />
		</article>
	</section>
</div>

<style>
	.deck {
		min-height: calc(100vh - 56px);
		padding: clamp(20px, 5vw, 60px);
	}
	.head {
		display: flex;
		flex-direction: column;
		gap: 8px;
		margin-bottom: 28px;
	}
	.head h1 {
		font-weight: 400;
		font-size: clamp(28px, 4vw, 42px);
		letter-spacing: -0.5px;
	}
	.sub {
		color: var(--muted);
		font-size: 14px;
		max-width: 70ch;
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(2, 1fr);
		gap: 16px;
	}
	.card {
		background: var(--panel);
		border: 1px solid var(--line2);
		border-radius: 14px;
		padding: 18px 20px;
		min-width: 0;
	}
	.card.wide {
		grid-column: 1 / -1;
	}
	.card-head {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 12px;
		margin-bottom: 12px;
	}
	.card-head h2 {
		font-weight: 600;
		font-size: 15px;
	}
	@media (max-width: 720px) {
		.grid {
			grid-template-columns: 1fr;
		}
	}
</style>
