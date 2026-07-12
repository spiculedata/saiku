<script lang="ts">
	import { base } from '$app/paths';
	import { riskBand, jurisdictionFlag } from '$lib/format';
	import GraphExplorer from '$lib/components/GraphExplorer.svelte';
	import type { PageData } from './$types';
	import type { OwnershipGraph } from '$lib/types';

	let { data }: { data: PageData } = $props();
	const band = $derived(riskBand(data.risk_score));

	let saveState = $state<'idle' | 'saving' | 'saved' | 'error'>('idle');
	async function saveCase() {
		if (saveState === 'saving' || saveState === 'saved') return;
		saveState = 'saving';
		try {
			const r = await fetch(`${base}/api/cases`, {
				method: 'POST',
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({
					title: data.name,
					kind: 'entity',
					subjectId: data.id,
					subjectName: data.name,
					jurisdiction: data.jurisdiction,
					note: `Risk ${data.risk_score?.toFixed?.(1) ?? '—'} · ${band.label}`,
					payload: {
						id: data.id,
						risk_score: data.risk_score,
						opacity_score: data.opacity_score,
						status: data.status
					}
				})
			});
			saveState = r.ok ? 'saved' : 'error';
		} catch {
			saveState = 'error';
		}
	}

	let graph: OwnershipGraph | undefined = $state();
	const owners = $derived.by(() => {
		if (!graph) return [];
		// registry data repeats the same person across paths — collapse by name,
		// and surface the largest declared stake we can see for them.
		const seen = new Map<string, { label: string; pct: number | null }>();
		for (const n of graph.nodes) {
			if (n.kind !== 'person' || seen.has(n.label)) continue;
			const pct = graph.edges
				.filter((e) => e.owner === n.id && e.percentage != null)
				.reduce<number | null>((max, e) => Math.max(max ?? 0, e.percentage as number), null);
			seen.set(n.label, { label: n.label, pct });
		}
		return [...seen.values()];
	});
</script>

<div class="investigation">
	<aside class="rail left">
		<div class="label">Subject</div>
		<h1 class="disp">{data.name}</h1>
		<div class="meta mono">
			{jurisdictionFlag(data.jurisdiction)} {data.jurisdiction ?? '—'} · {data.status ?? '—'} · {data.id}
		</div>
		<div class="cards">
			<div class="card">
				<div class="v" style="color:{band.color}">{data.risk_score?.toFixed?.(1) ?? '—'}</div>
				<div class="t">Risk · {band.label}</div>
			</div>
			<div class="card">
				<div class="v">{data.opacity_score?.toFixed?.(2) ?? '—'}</div>
				<div class="t">Opacity</div>
			</div>
		</div>
		<button class="save-case" disabled={saveState === 'saving' || saveState === 'saved'} onclick={saveCase}>
			{#if saveState === 'saved'}✓ Saved to cases
			{:else if saveState === 'saving'}Saving…
			{:else if saveState === 'error'}Save failed — retry
			{:else}＋ Save to case{/if}
		</button>
		<div class="legend mono">
			<div class="label">Legend</div>
			<div class="row"><span class="dot subject"></span> Subject</div>
			<div class="row"><span class="dot entity"></span> Entity</div>
			<div class="row"><span class="dot person"></span> Owner</div>
			<div class="row"><span class="dot cycle"></span> Circular</div>
		</div>
	</aside>

	<section class="center">
		<GraphExplorer rootId={data.id} onGraph={(g) => (graph = g)} />
	</section>

	<aside class="rail right">
		<div class="label">Owners</div>
		<h2 class="disp">Beneficial owners</h2>
		{#if owners.length === 0}
			<p class="hint mono">None found in the traversed graph.</p>
		{:else}
			<ul class="owners">
				{#each owners as o (o.label)}
					<li>
						{#if o.pct != null}<span class="pct mono">{Math.round(o.pct)}%</span>{/if}
						<div class="name">{o.label}</div>
						<div class="tag mono">beneficial owner</div>
					</li>
				{/each}
			</ul>
		{/if}
	</aside>
</div>

<style>
	.investigation {
		display: grid;
		grid-template-columns: 270px 1fr 300px;
		height: calc(100vh - 56px);
	}
	.rail {
		padding: 24px;
		overflow-y: auto;
	}
	.rail.left {
		border-right: 1px solid var(--line2);
		background: var(--panel);
	}
	.rail.right {
		border-left: 1px solid var(--line2);
		background: var(--panel);
	}
	.center {
		position: relative;
		min-width: 0;
	}
	h1 {
		font-weight: 600;
		font-size: 26px;
		margin: 8px 0 6px;
	}
	h2 {
		font-weight: 600;
		font-size: 18px;
		margin: 6px 0 14px;
	}
	.meta {
		color: var(--muted);
		font-size: 12px;
	}
	.cards {
		display: flex;
		flex-direction: column;
		gap: 10px;
		margin: 22px 0;
	}
	.card {
		background: var(--panel2);
		border: 1px solid var(--line2);
		border-radius: 10px;
		padding: 12px 16px;
	}
	.card .v {
		font-family: var(--mono);
		font-size: 22px;
		font-weight: 700;
	}
	.card .t {
		color: var(--muted);
		font-size: 11px;
		margin-top: 4px;
	}
	.save-case {
		width: 100%;
		background: transparent;
		border: 1px solid var(--line2);
		color: var(--muted);
		border-radius: 9px;
		padding: 10px 14px;
		font-size: 13px;
		cursor: pointer;
	}
	.save-case:hover:not(:disabled) {
		border-color: var(--cyan);
		color: var(--cyan);
	}
	.save-case:disabled {
		cursor: default;
		color: var(--accent-2, #5fe0a0);
		border-color: rgba(95, 224, 160, 0.4);
	}
	.legend {
		margin-top: 28px;
		font-size: 12px;
	}
	.legend .row {
		display: flex;
		align-items: center;
		gap: 8px;
		color: var(--muted);
		margin-top: 8px;
	}
	.dot {
		width: 10px;
		height: 10px;
		border-radius: 50%;
		border: 2px solid var(--line2);
		flex-shrink: 0;
	}
	.dot.subject {
		border-color: var(--cyan);
	}
	.dot.entity {
		border-color: #c8ccda;
	}
	.dot.person {
		border-color: var(--amber);
	}
	.dot.cycle {
		border-color: var(--red);
		border-style: dashed;
	}
	.owners {
		list-style: none;
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.owners li {
		background: var(--panel2);
		border: 1px solid var(--line2);
		border-radius: 10px;
		padding: 10px 14px;
	}
	.owners .pct {
		float: right;
		color: var(--amber);
		font-weight: 700;
		font-size: 14px;
	}
	.owners .name {
		font-size: 13px;
	}
	.owners .tag {
		color: var(--amber);
		font-size: 10px;
		text-transform: uppercase;
		letter-spacing: 1px;
		margin-top: 4px;
	}
	.hint {
		color: var(--dim);
		font-size: 12px;
	}
</style>
