<script lang="ts">
	import { base } from '$app/paths';
	import type { PageData } from './$types';
	import { STATUS_META, PRIORITY_META, STATUS_ORDER } from '$lib/caseMeta';

	let { data }: { data: PageData } = $props();

	function shortDate(iso: string): string {
		return new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short' });
	}
	const total = $derived(STATUS_ORDER.reduce((n, s) => n + (data.summary[s] ?? 0), 0));
</script>

<div class="cases grid-bg">
	<header class="head">
		<div class="label">Casework · write-back to Postgres</div>
		<h1 class="disp">Cases</h1>
		<p class="sub">
			Pinned entities and saved Ask results become working cases — each with a status, a priority and
			an activity trail. This is the demo's write path; everything else reads the warehouse through Saiku.
		</p>
	</header>

	{#if !data.enabled}
		<div class="notice mono">⚠ The write-back store isn't configured (no DATABASE_URL).</div>
	{:else}
		<section class="summary">
			<a class="pill" class:on={data.status === null} href="{base}/cases">
				<span class="n mono">{total}</span><span class="k">All</span>
			</a>
			{#each STATUS_ORDER as s}
				<a
					class="pill"
					class:on={data.status === s}
					style="--tone:{STATUS_META[s].color}"
					href="{base}/cases?status={s}"
				>
					<span class="n mono">{data.summary[s] ?? 0}</span><span class="k">{STATUS_META[s].label}</span>
				</a>
			{/each}
		</section>

		{#if data.cases.length === 0}
			<div class="empty">
				<div class="empty-title disp">{data.status ? 'Nothing in this queue' : 'No cases yet'}</div>
				<p class="mono">
					Save an entity from <a href="{base}/">a profile</a>, an answer from
					<a href="{base}/ask">Ask</a>, or the featured <a href="{base}/reveal">case file</a>.
				</p>
			</div>
		{:else}
			<section class="grid">
				{#each data.cases as c (c.id)}
					<a class="card" href="{base}/cases/{c.id}">
						<div class="card-top">
							<span class="kind mono" data-kind={c.kind}>{c.kind}</span>
							<span class="status mono" style="--tone:{STATUS_META[c.status].color}">
								{STATUS_META[c.status].label}
							</span>
						</div>
						<div class="title">{c.kind === 'ask' ? c.title : (c.subjectName ?? c.title)}</div>
						<div class="meta mono">
							{#if c.priority === 'high'}<span class="prio" style="color:{PRIORITY_META.high.color}">⚑ High</span>{/if}
							{#if c.jurisdiction}<span class="jur">{c.jurisdiction}</span>{/if}
							{#if c.assignee}<span>@{c.assignee}</span>{/if}
						</div>
						{#if c.note}<div class="note">{c.note}</div>{/if}
						<div class="foot mono">
							<span>
								{#if c.activityCount}{c.activityCount} event{c.activityCount === 1 ? '' : 's'}{/if}
								{#if c.threadCount}· {c.threadCount} thread{c.threadCount === 1 ? '' : 's'}{/if}
							</span>
							<span class="date">{shortDate(c.createdAt)}</span>
						</div>
					</a>
				{/each}
			</section>
		{/if}
	{/if}
</div>

<style>
	.cases {
		min-height: calc(100vh - 56px);
		padding: clamp(20px, 5vw, 60px);
	}
	.head {
		display: flex;
		flex-direction: column;
		gap: 8px;
		margin-bottom: 22px;
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
	.notice {
		background: rgba(245, 181, 68, 0.1);
		border: 1px solid rgba(245, 181, 68, 0.4);
		color: var(--amber);
		border-radius: 10px;
		padding: 12px 16px;
		font-size: 12.5px;
	}

	.summary {
		display: flex;
		flex-wrap: wrap;
		gap: 10px;
		margin-bottom: 20px;
	}
	.pill {
		display: flex;
		align-items: baseline;
		gap: 8px;
		background: var(--panel);
		border: 1px solid var(--line2);
		border-left: 3px solid var(--tone, var(--line2));
		border-radius: 10px;
		padding: 10px 16px;
	}
	.pill.on {
		background: var(--panel2);
		border-color: var(--tone, var(--cyan));
	}
	.pill .n {
		font-size: 20px;
		font-weight: 700;
		color: var(--fg);
	}
	.pill .k {
		font-size: 11px;
		text-transform: uppercase;
		letter-spacing: 1px;
		color: var(--muted);
	}

	.empty {
		border: 1px dashed var(--line2);
		border-radius: 14px;
		padding: 60px 24px;
		text-align: center;
		color: var(--muted);
	}
	.empty-title {
		font-size: 22px;
		color: var(--fg);
		margin-bottom: 8px;
	}
	.empty a {
		color: var(--cyan);
	}

	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
		gap: 14px;
	}
	.card {
		background: var(--panel);
		border: 1px solid var(--line2);
		border-radius: 12px;
		padding: 14px 16px;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.card:hover {
		border-color: var(--cyan);
	}
	.card-top {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}
	.kind {
		font-size: 9px;
		text-transform: uppercase;
		letter-spacing: 1px;
		padding: 2px 8px;
		border-radius: 5px;
		background: rgba(87, 214, 230, 0.14);
		color: var(--cyan);
	}
	.kind[data-kind='ask'] {
		background: rgba(245, 181, 68, 0.14);
		color: var(--amber);
	}
	.status {
		font-size: 10px;
		text-transform: uppercase;
		letter-spacing: 0.5px;
		color: var(--tone);
		border: 1px solid color-mix(in srgb, var(--tone) 40%, transparent);
		border-radius: 5px;
		padding: 2px 8px;
	}
	.title {
		font-size: 15px;
		color: var(--fg);
		line-height: 1.35;
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		gap: 10px;
		font-size: 11px;
		color: var(--muted);
	}
	.meta .jur {
		color: var(--amber);
	}
	.meta .prio {
		font-weight: 600;
	}
	.note {
		font-size: 12px;
		color: var(--muted);
		line-height: 1.4;
	}
	.foot {
		display: flex;
		justify-content: space-between;
		gap: 10px;
		margin-top: 4px;
		font-size: 10.5px;
		color: var(--dim);
	}
</style>
