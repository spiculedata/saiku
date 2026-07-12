<script lang="ts">
	import { base } from '$app/paths';
	import { riskBand, jurisdictionFlag } from '$lib/format';
	import GraphExplorer from '$lib/components/GraphExplorer.svelte';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();
	const band = $derived(riskBand(data.subject?.risk_score ?? null));

	let saveState = $state<'idle' | 'saving' | 'saved' | 'error'>('idle');
	async function saveCase() {
		if (!data.subject || saveState === 'saving' || saveState === 'saved') return;
		saveState = 'saving';
		try {
			const r = await fetch(`${base}/api/cases`, {
				method: 'POST',
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({
					title: data.subject.name,
					kind: 'entity',
					subjectId: data.id,
					subjectName: data.subject.name,
					jurisdiction: data.subject.jurisdiction,
					note: 'Featured case file'
				})
			});
			saveState = r.ok ? 'saved' : 'error';
		} catch {
			saveState = 'error';
		}
	}
</script>

<div class="reveal grid-bg">
	{#if !data.subject}
		<div class="unavailable mono">This week's case file is unavailable.</div>
	{:else}
		<header class="head">
			<div class="label">Case file · featured this week</div>
			<h1 class="disp">{data.subject.name}</h1>
			<div class="chips mono">
				<span class="chip">{jurisdictionFlag(data.subject.jurisdiction)} {data.subject.jurisdiction ?? '—'}</span>
				<span class="chip">{data.subject.status ?? '—'}</span>
				<span class="chip risk" style="color:{band.color};border-color:{band.color}44">
					risk {data.subject.risk_score?.toFixed?.(1) ?? '—'} · {band.label}
				</span>
				{#if data.subject.opacity_score != null}
					<span class="chip">opacity {data.subject.opacity_score.toFixed(2)}</span>
				{/if}
			</div>
			<p class="hook">
				{#if data.stats.hasGraph}
					A {data.subject.jurisdiction ?? '—'}-registered company at
					<em style="color:{band.color}">{band.label.toLowerCase()}</em> computed risk. Its ownership
					climbs through <strong>{data.stats.layers}</strong> corporate
					{data.stats.layers === 1 ? 'layer' : 'layers'} and
					<strong>{data.stats.depth}</strong> levels of control{#if data.stats.owners > 0}, resolving to
						<strong>{data.stats.owners}</strong> {data.stats.owners === 1 ? 'person' : 'people'}{/if}.{#if data.stats.hasCycle}
						One of those links loops back on itself — the ownership is circular.{/if}
				{:else}
					A {data.subject.jurisdiction ?? '—'}-registered company at {band.label.toLowerCase()} computed risk.
				{/if}
			</p>
		</header>

		{#if data.stats.hasGraph}
			<section class="stage">
				<GraphExplorer rootId={data.id} />
			</section>

			<section class="story">
				<article class="col">
					<div class="col-head">
						<h2 class="disp">The layers</h2>
						<span class="count mono">{data.layers.length}</span>
					</div>
					<p class="col-sub">Corporate entities the ownership passes through before it reaches a person.</p>
					<ol class="chain">
						{#each data.layers as layer, i}
							<li><span class="n mono">{String(i + 1).padStart(2, '0')}</span><span class="ent">{layer}</span></li>
						{/each}
					</ol>
				</article>

				<article class="col">
					<div class="col-head">
						<h2 class="disp">The people</h2>
						<span class="count mono">{data.people.length}</span>
					</div>
					<p class="col-sub">The natural persons at the end of the chain — the humans in control.</p>
					{#if data.people.length === 0}
						<p class="none mono">No natural persons surfaced in the traversed chain — control resolves only to other companies.</p>
					{:else}
						<ul class="people">
							{#each data.people as person}
								<li><span class="dot"></span>{person}</li>
							{/each}
						</ul>
					{/if}
				</article>
			</section>

			<section class="why">
				<h2 class="disp">Why it's on the board</h2>
				<p>
					None of these links is hidden — each is a filing in a public register. What's hard is
					<em>seeing them together</em>: a chain that crosses borders and ownership types tends to sit
					in a different filing, a different registry, a different language at every hop. Enigma walks
					the whole structure in one pass and scores it, which is why this one surfaced. The risk figure
					is Benafide's computed signal, not a verdict — it's a prompt to look closer, and the graph
					above is where looking closer starts.
				</p>
			</section>
		{/if}

		<div class="actions">
			<a class="primary" href="{base}/e/{data.id}">Open full investigation →</a>
			<button class="ghost" disabled={saveState === 'saving' || saveState === 'saved'} onclick={saveCase}>
				{#if saveState === 'saved'}✓ Saved to cases
				{:else if saveState === 'saving'}Saving…
				{:else if saveState === 'error'}Save failed — retry
				{:else}＋ Save to case{/if}
			</button>
		</div>
	{/if}
</div>

<style>
	.reveal {
		min-height: calc(100vh - 56px);
		padding: clamp(20px, 5vw, 60px);
		max-width: 1100px;
		margin: 0 auto;
	}
	.head {
		display: flex;
		flex-direction: column;
		gap: 12px;
		margin-bottom: 26px;
	}
	.head h1 {
		font-weight: 400;
		font-size: clamp(30px, 5vw, 52px);
		letter-spacing: -1px;
		line-height: 1.02;
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
	}
	.chip {
		font-size: 11px;
		padding: 4px 10px;
		border-radius: 6px;
		background: var(--panel);
		border: 1px solid var(--line2);
		color: var(--muted);
	}
	.chip.risk {
		font-weight: 600;
	}
	.hook {
		font-size: clamp(16px, 2vw, 20px);
		line-height: 1.55;
		color: var(--fg);
		max-width: 68ch;
		margin-top: 4px;
	}
	.hook em {
		font-style: italic;
	}
	.hook strong {
		color: var(--cyan);
		font-weight: 600;
	}

	.stage {
		height: 480px;
		border: 1px solid var(--line2);
		border-radius: 16px;
		overflow: hidden;
		margin-bottom: 22px;
		background: var(--panel);
	}

	.story {
		display: grid;
		grid-template-columns: 1.1fr 1fr;
		gap: 16px;
		margin-bottom: 22px;
	}
	.col {
		background: var(--panel);
		border: 1px solid var(--line2);
		border-radius: 14px;
		padding: 20px 22px;
	}
	.col-head {
		display: flex;
		align-items: baseline;
		gap: 10px;
	}
	.col-head h2 {
		font-weight: 600;
		font-size: 17px;
	}
	.count {
		color: var(--amber);
		font-size: 13px;
	}
	.col-sub {
		color: var(--muted);
		font-size: 12.5px;
		margin: 6px 0 14px;
		line-height: 1.45;
	}
	.chain {
		list-style: none;
		display: flex;
		flex-direction: column;
	}
	.chain li {
		display: flex;
		gap: 12px;
		align-items: baseline;
		padding: 9px 2px;
		border-bottom: 1px solid var(--line);
	}
	.chain li:last-child {
		border-bottom: none;
	}
	.chain .n {
		color: var(--dim);
		font-size: 11px;
		flex: none;
	}
	.chain .ent {
		font-size: 13.5px;
		line-height: 1.35;
	}
	.people {
		list-style: none;
		display: flex;
		flex-direction: column;
		gap: 11px;
	}
	.people li {
		display: flex;
		align-items: center;
		gap: 10px;
		font-size: 13.5px;
	}
	.people .dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--amber);
		flex: none;
	}
	.none {
		color: var(--dim);
		font-size: 12.5px;
		line-height: 1.5;
	}

	.why {
		background: linear-gradient(180deg, rgba(87, 214, 230, 0.05), transparent);
		border: 1px solid var(--line2);
		border-radius: 14px;
		padding: 22px 24px;
		margin-bottom: 22px;
	}
	.why h2 {
		font-weight: 600;
		font-size: 17px;
		margin-bottom: 10px;
	}
	.why p {
		color: var(--muted);
		font-size: 14.5px;
		line-height: 1.6;
		max-width: 78ch;
	}
	.why em {
		font-style: italic;
		color: var(--fg);
	}

	.actions {
		display: flex;
		gap: 12px;
		flex-wrap: wrap;
	}
	.primary {
		background: var(--cyan);
		color: var(--bg);
		border-radius: 9px;
		padding: 12px 20px;
		font-weight: 600;
		font-size: 14px;
	}
	.ghost {
		background: transparent;
		border: 1px solid var(--line2);
		color: var(--muted);
		border-radius: 9px;
		padding: 12px 18px;
		font-size: 14px;
		cursor: pointer;
	}
	.ghost:hover:not(:disabled) {
		border-color: var(--cyan);
		color: var(--cyan);
	}
	.ghost:disabled {
		cursor: default;
		color: var(--accent-2, #5fe0a0);
		border-color: rgba(95, 224, 160, 0.4);
	}
	.unavailable {
		color: var(--muted);
		text-align: center;
		padding: 80px 0;
	}

	@media (max-width: 820px) {
		.story {
			grid-template-columns: 1fr;
		}
	}
</style>
