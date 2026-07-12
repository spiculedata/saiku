<script lang="ts">
	import { riskBand, jurisdictionFlag } from '$lib/format';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();
	const band = $derived(riskBand(data.risk?.risk_score));
</script>

<section class="profile grid-bg">
	<div class="label">Subject</div>
	<h1 class="disp">{data.entity.name}</h1>
	<div class="meta mono">
		{jurisdictionFlag(data.entity.jurisdiction)} {data.entity.jurisdiction ?? '—'} · {data.entity.status ?? '—'} · {data.entity.id}
	</div>
	<div class="cards">
		<div class="card">
			<div class="v" style="color:{band.color}">{data.risk?.risk_score?.toFixed?.(2) ?? '—'}</div>
			<div class="t">Risk · {band.label}</div>
		</div>
		<div class="card">
			<div class="v">{data.risk?.opacity_score?.toFixed?.(2) ?? '—'}</div>
			<div class="t">Opacity</div>
		</div>
	</div>
	<p class="hint mono">Ownership graph (The Web) arrives in Phase 1 →</p>
</section>

<style>
	.profile {
		min-height: calc(100vh - 56px);
		padding: 48px clamp(20px, 6vw, 80px);
	}
	h1 {
		font-weight: 600;
		font-size: clamp(28px, 4vw, 44px);
		margin: 8px 0 6px;
	}
	.meta {
		color: var(--muted);
		font-size: 13px;
	}
	.cards {
		display: flex;
		gap: 14px;
		margin: 26px 0;
	}
	.card {
		background: var(--panel);
		border: 1px solid var(--line2);
		border-radius: 12px;
		padding: 16px 20px;
		min-width: 150px;
	}
	.card .v {
		font-family: var(--mono);
		font-size: 26px;
		font-weight: 700;
	}
	.card .t {
		color: var(--muted);
		font-size: 12px;
		margin-top: 4px;
	}
	.hint {
		color: var(--dim);
		margin-top: 20px;
	}
</style>
