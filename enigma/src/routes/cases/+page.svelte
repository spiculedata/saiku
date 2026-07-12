<script lang="ts">
	import { base } from '$app/paths';
	import { invalidateAll } from '$app/navigation';
	import type { PageData } from './$types';
	import type { SavedCase } from '$lib/server/store';

	let { data }: { data: PageData } = $props();
	let cases = $state<SavedCase[]>(data.cases);
	$effect(() => {
		cases = data.cases;
	});

	function shortDate(iso: string): string {
		const d = new Date(iso);
		return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
	}

	async function remove(id: string) {
		const r = await fetch(`${base}/api/cases/${id}`, { method: 'DELETE' });
		if (r.ok) {
			cases = cases.filter((c) => c.id !== id);
			await invalidateAll();
		}
	}
</script>

<div class="cases grid-bg">
	<header class="head">
		<div class="label">Saved investigations · write-back to Postgres</div>
		<h1 class="disp">Cases</h1>
		<p class="sub">
			Pinned entities and saved Ask results. This is the demo's write path — everything else reads
			the warehouse through Saiku; cases are written back to a Postgres store.
		</p>
	</header>

	{#if !data.enabled}
		<div class="notice mono">⚠ The write-back store isn't configured (no DATABASE_URL).</div>
	{:else if cases.length === 0}
		<div class="empty">
			<div class="empty-title disp">No cases yet</div>
			<p class="mono">
				Save an entity from <a href="{base}/">a profile</a> or an answer from
				<a href="{base}/ask">Ask</a> to pin it here.
			</p>
		</div>
	{:else}
		<section class="grid">
			{#each cases as c (c.id)}
				<article class="card">
					<div class="card-top">
						<span class="kind mono" data-kind={c.kind}>{c.kind}</span>
						<button class="del" title="Delete case" onclick={() => remove(c.id)}>✕</button>
					</div>
					{#if c.kind === 'ask'}
						<div class="title">{c.title}</div>
						{#if c.note}<div class="note mono">↳ {c.note}</div>{/if}
					{:else}
						<a class="title link" href="{base}/e/{c.subjectId}">{c.subjectName ?? c.title}</a>
						<div class="meta mono">
							{#if c.jurisdiction}<span class="jur">{c.jurisdiction}</span>{/if}
							{#if c.subjectId}<span class="id">{c.subjectId}</span>{/if}
						</div>
						{#if c.note}<div class="note">{c.note}</div>{/if}
					{/if}
					<div class="foot mono">
						{#if c.threadCount}<span>{c.threadCount} thread{c.threadCount === 1 ? '' : 's'}</span>{/if}
						<span class="date">{shortDate(c.createdAt)}</span>
					</div>
				</article>
			{/each}
		</section>
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
	.notice {
		background: rgba(245, 181, 68, 0.1);
		border: 1px solid rgba(245, 181, 68, 0.4);
		color: var(--amber);
		border-radius: 10px;
		padding: 12px 16px;
		font-size: 12.5px;
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
	.empty a,
	.link {
		color: var(--cyan);
	}

	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
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
	.del {
		background: transparent;
		border: none;
		color: var(--dim);
		cursor: pointer;
		font-size: 13px;
		padding: 2px 4px;
	}
	.del:hover {
		color: var(--red);
	}
	.title {
		font-size: 15px;
		color: var(--fg);
		line-height: 1.35;
	}
	a.title:hover {
		color: var(--cyan);
	}
	.meta {
		display: flex;
		gap: 10px;
		font-size: 11px;
		color: var(--muted);
	}
	.meta .jur {
		color: var(--amber);
	}
	.meta .id {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
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
