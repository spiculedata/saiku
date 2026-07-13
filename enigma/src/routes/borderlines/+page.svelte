<script lang="ts">
	import FlowChart from '$lib/components/FlowChart.svelte';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();
	const nf = new Intl.NumberFormat('en-GB');
	const compact = new Intl.NumberFormat('en-GB', { notation: 'compact', maximumFractionDigits: 1 });

	const maxCorridor = $derived(data.corridors.reduce((m, c) => Math.max(m, c.count), 0));
	function barWidth(count: number): string {
		if (maxCorridor <= 0) return '0%';
		return `${Math.max(3, Math.round((count / maxCorridor) * 100))}%`;
	}
</script>

<div class="borderlines grid-bg">
	<header class="head">
		<div class="label">Cross-border ownership · live over the Ossie model</div>
		<h1 class="disp">Borderlines</h1>
		<p class="sub">
			Where beneficial owners sit in a different country from the companies they control. Owner
			nationality flows into company jurisdiction — every corridor is a live aggregate through Saiku.
		</p>
	</header>

	<section class="stat-band">
		<div class="stat" data-tone="cyan">
			<div class="stat-value mono">{compact.format(data.stats.crossBorder)}</div>
			<div class="stat-label">Cross-border interests</div>
		</div>
		<div class="stat" data-tone="amber">
			<div class="stat-value mono">{data.stats.foreignSharePct}%</div>
			<div class="stat-label">Held across a border</div>
		</div>
		<div class="stat">
			<div class="stat-value mono">{data.stats.ownerCountries}</div>
			<div class="stat-label">Owner countries</div>
		</div>
		<div class="stat" data-tone="cyan">
			{#if data.stats.topCorridor}
				<div class="stat-value corridor mono">
					{data.stats.topCorridor.ownerCode} <span class="arrow">→</span> {data.stats.topCorridor.jurisCode}
				</div>
				<div class="stat-label">Top corridor · {compact.format(data.stats.topCorridor.count)}</div>
			{:else}
				<div class="stat-value mono">—</div>
				<div class="stat-label">Top corridor</div>
			{/if}
		</div>
	</section>

	<section class="grid">
		<article class="card flow">
			<div class="card-head">
				<h2 class="disp">Ownership corridors</h2>
				<div class="label">Owner country <span class="ck">→</span> company jurisdiction</div>
			</div>
			{#if data.flow.links.length === 0}
				<div class="empty mono">No cross-border corridors resolved.</div>
			{:else}
				<FlowChart data={data.flow} />
			{/if}
		</article>

		<article class="card ranks-card">
			<div class="card-head">
				<h2 class="disp">Top corridors</h2>
				<div class="label">By interest count</div>
			</div>
			<ol class="ranks">
				{#each data.corridors as c, i}
					<li class="rank">
						<span class="rank-num mono">{i + 1}</span>
						<span class="rank-route">
							<span class="from">{c.ownerName}</span>
							<span class="arrow mono">→</span>
							<span class="to">{c.jurisName}</span>
						</span>
						<span class="rank-bar"><span class="rank-fill" style:width={barWidth(c.count)}></span></span>
						<span class="rank-count mono">{nf.format(c.count)}</span>
					</li>
				{/each}
			</ol>
		</article>
	</section>
</div>

<style>
	.borderlines {
		min-height: calc(100vh - 56px);
		padding: clamp(20px, 5vw, 60px);
	}
	.head {
		display: flex;
		flex-direction: column;
		gap: 8px;
		margin-bottom: 24px;
	}
	.head h1 {
		font-weight: 400;
		font-size: clamp(28px, 4vw, 42px);
		letter-spacing: -0.5px;
	}
	.sub {
		color: var(--muted);
		font-size: 14px;
		max-width: 76ch;
	}

	.stat-band {
		display: grid;
		grid-template-columns: repeat(4, 1fr);
		gap: 14px;
		margin-bottom: 20px;
	}
	.stat {
		background: var(--panel);
		border: 1px solid var(--line2);
		border-left: 3px solid var(--line2);
		border-radius: 12px;
		padding: 16px 18px;
	}
	.stat[data-tone='cyan'] {
		border-left-color: var(--cyan);
	}
	.stat[data-tone='amber'] {
		border-left-color: var(--amber);
	}
	.stat-value {
		font-size: 28px;
		font-weight: 600;
		letter-spacing: -1px;
	}
	.stat-value.corridor {
		font-size: 22px;
	}
	.stat-value .arrow {
		color: var(--muted);
	}
	.stat[data-tone='amber'] .stat-value {
		color: var(--amber);
	}
	.stat-label {
		color: var(--muted);
		font-size: 12px;
		text-transform: uppercase;
		letter-spacing: 1px;
		margin-top: 4px;
	}

	.grid {
		display: grid;
		grid-template-columns: 1.35fr 1fr;
		gap: 16px;
	}
	.card {
		background: var(--panel);
		border: 1px solid var(--line2);
		border-radius: 14px;
		padding: 18px 20px;
		min-width: 0;
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
	.card-head .ck {
		color: var(--cyan);
	}

	.ranks {
		list-style: none;
		display: flex;
		flex-direction: column;
	}
	.rank {
		display: grid;
		grid-template-columns: 24px 1fr 84px 68px;
		align-items: center;
		gap: 12px;
		padding: 9px 4px;
		border-bottom: 1px solid var(--line);
	}
	.rank:last-child {
		border-bottom: none;
	}
	.rank-num {
		font-size: 12px;
		color: var(--dim);
		text-align: right;
	}
	.rank-route {
		display: flex;
		align-items: center;
		gap: 7px;
		font-size: 12.5px;
		min-width: 0;
	}
	.rank-route .from,
	.rank-route .to {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.rank-route .to {
		color: var(--amber);
	}
	.rank-route .arrow {
		color: var(--muted);
		flex: none;
	}
	.rank-bar {
		height: 6px;
		background: var(--line);
		border-radius: 3px;
		overflow: hidden;
	}
	.rank-fill {
		display: block;
		height: 100%;
		background: linear-gradient(90deg, var(--cyan), var(--amber));
		border-radius: 3px;
	}
	.rank-count {
		font-size: 12px;
		color: var(--fg);
		text-align: right;
	}

	.empty {
		color: var(--muted);
		font-size: 13px;
		padding: 40px 0;
		text-align: center;
	}

	@media (max-width: 860px) {
		.stat-band {
			grid-template-columns: repeat(2, 1fr);
		}
		.grid {
			grid-template-columns: 1fr;
		}
	}
</style>
