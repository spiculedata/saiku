<script lang="ts">
	import { base } from '$app/paths';
	import DeckChart from '$lib/components/DeckChart.svelte';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();

	const nf = new Intl.NumberFormat('en-GB');

	const stats = $derived([
		{ value: data.signals.stats.totalFlags, label: 'Screening hits', tone: 'cyan' },
		{ value: data.signals.stats.sanctionFlags, label: 'Sanctions matches', tone: 'red' },
		{ value: data.signals.stats.highRiskEntities, label: 'High-risk entities', tone: 'amber' },
		{ value: data.signals.stats.distinctTopics, label: 'Risk categories', tone: 'muted' }
	]);

	function humaniseStatus(raw: string | null): string {
		if (!raw) return '—';
		return raw.replace(/[_-]/g, ' ');
	}

	function isSanction(topic: string): boolean {
		return topic.toLowerCase().includes('sanction');
	}

	function riskWidth(score: number | null, max: number): string {
		if (!score || max <= 0) return '0%';
		return `${Math.max(4, Math.round((score / max) * 100))}%`;
	}

	const maxRisk = $derived(
		data.signals.topRisk.reduce((m, e) => Math.max(m, e.riskScore ?? 0), 0)
	);
</script>

<div class="signals grid-bg">
	<header class="head">
		<div class="label">Risk radar · live over the Ossie model</div>
		<h1 class="disp">Signals</h1>
		<p class="sub">
			Sanctions, debarment, PEP and export-control screening hits — cross-referenced with computed
			entity risk. Every count is a live read of the Benafide warehouse through Saiku.
		</p>
	</header>

	<section class="stat-band">
		{#each stats as s}
			<div class="stat" data-tone={s.tone}>
				<div class="stat-value mono">{nf.format(s.value)}</div>
				<div class="stat-label">{s.label}</div>
			</div>
		{/each}
	</section>

	<section class="grid">
		<article class="card feed">
			<div class="card-head">
				<h2 class="disp">Screening feed</h2>
				<div class="label">Sanctions first · {data.signals.flags.length} shown</div>
			</div>
			<div class="feed-scroll">
				{#if data.signals.flags.length === 0}
					<div class="empty mono">No screening hits available.</div>
				{:else}
					{#each data.signals.flags as flag}
						<div class="flag">
							<div class="flag-main">
								<div class="flag-name">{flag.name ?? '—'}</div>
								<div class="chips">
									{#each flag.topics as topic}
										<span class="chip" class:sanction={isSanction(topic)}>{topic}</span>
									{/each}
									{#if flag.topics.length === 0}<span class="chip muted">unclassified</span>{/if}
								</div>
							</div>
							<div class="flag-meta mono">{humaniseStatus(flag.status)}</div>
						</div>
					{/each}
				{/if}
			</div>
		</article>

		<article class="card">
			<div class="card-head">
				<h2 class="disp">By category</h2>
				<div class="label">Matched screening lists</div>
			</div>
			<DeckChart type="hbar" data={data.topicRows} color="#ff5d6c" />
			<p class="note">
				One flag can match several lists — a single hit counts once per category it triggers.
			</p>
		</article>
	</section>

	<section class="card leaderboard">
		<div class="card-head">
			<h2 class="disp">Highest computed risk</h2>
			<div class="label">Top {data.signals.topRisk.length} companies · click to investigate</div>
		</div>
		{#if data.signals.topRisk.length === 0}
			<div class="empty mono">No risk-scored entities available.</div>
		{:else}
			<ol class="ranks">
				{#each data.signals.topRisk as e, i}
					<li>
						<a class="rank" href="{base}/e/{e.id}">
							<span class="rank-num mono">{i + 1}</span>
							<span class="rank-name">{e.name ?? e.id}</span>
							<span class="rank-jur mono">{e.jurisdiction ?? '—'}</span>
							<span class="rank-bar"><span class="rank-fill" style:width={riskWidth(e.riskScore, maxRisk)}></span></span>
							<span class="rank-score mono">{e.riskScore?.toFixed(1) ?? '—'}</span>
						</a>
					</li>
				{/each}
			</ol>
		{/if}
	</section>
</div>

<style>
	.signals {
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
		max-width: 74ch;
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
	.stat[data-tone='red'] {
		border-left-color: var(--red);
	}
	.stat[data-tone='amber'] {
		border-left-color: var(--amber);
	}
	.stat-value {
		font-size: 28px;
		font-weight: 600;
		letter-spacing: -1px;
	}
	.stat[data-tone='red'] .stat-value {
		color: var(--red);
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
		grid-template-columns: 1.15fr 1fr;
		gap: 16px;
		margin-bottom: 16px;
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

	.feed-scroll {
		max-height: 340px;
		overflow-y: auto;
		display: flex;
		flex-direction: column;
	}
	.flag {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 14px;
		padding: 11px 2px;
		border-bottom: 1px solid var(--line);
	}
	.flag:last-child {
		border-bottom: none;
	}
	.flag-name {
		font-size: 13px;
		line-height: 1.35;
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: 5px;
		margin-top: 6px;
	}
	.chip {
		font-family: var(--mono);
		font-size: 10px;
		padding: 2px 7px;
		border-radius: 5px;
		background: rgba(139, 144, 163, 0.14);
		color: var(--muted);
		border: 1px solid var(--line2);
	}
	.chip.sanction {
		background: rgba(255, 93, 108, 0.14);
		color: var(--red);
		border-color: rgba(255, 93, 108, 0.4);
	}
	.flag-meta {
		font-size: 10px;
		color: var(--dim);
		white-space: nowrap;
		text-transform: uppercase;
		letter-spacing: 0.5px;
		padding-top: 2px;
	}
	.note {
		color: var(--dim);
		font-size: 11px;
		margin-top: 6px;
	}

	.leaderboard .ranks {
		list-style: none;
		display: flex;
		flex-direction: column;
	}
	.rank {
		display: grid;
		grid-template-columns: 28px 1fr 42px 140px 44px;
		align-items: center;
		gap: 14px;
		padding: 10px 6px;
		border-bottom: 1px solid var(--line);
		border-radius: 8px;
	}
	.rank:hover {
		background: rgba(87, 214, 230, 0.06);
	}
	.rank-num {
		font-size: 12px;
		color: var(--dim);
		text-align: right;
	}
	.rank-name {
		font-size: 13px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.rank-jur {
		font-size: 11px;
		color: var(--muted);
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
		background: linear-gradient(90deg, var(--amber), var(--red));
		border-radius: 3px;
	}
	.rank-score {
		font-size: 13px;
		color: var(--amber);
		text-align: right;
	}

	.empty {
		color: var(--muted);
		font-size: 13px;
		padding: 24px 0;
		text-align: center;
	}

	@media (max-width: 860px) {
		.stat-band {
			grid-template-columns: repeat(2, 1fr);
		}
		.grid {
			grid-template-columns: 1fr;
		}
		.rank {
			grid-template-columns: 24px 1fr 60px;
		}
		.rank-bar {
			display: none;
		}
	}
</style>
