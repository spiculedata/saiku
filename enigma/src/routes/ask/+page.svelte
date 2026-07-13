<script lang="ts">
	import { base } from '$app/paths';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();

	interface Cell {
		value?: number;
		formatted?: string;
	}
	interface AskColumn {
		key: string;
		label: string;
		type: string;
	}
	interface Turn {
		question: string;
		model: string | null;
		queryUsed: unknown;
		columns: AskColumn[];
		records: Record<string, unknown>[];
		error?: { code: string; message: string; field?: string; available?: string[] };
		saved?: boolean;
	}

	let question = $state('');
	let turns = $state<Turn[]>([]);
	let busy = $state(false);
	const configured = data.health.configured;

	const ROW_CAP = 12;

	function cellText(v: unknown): string {
		if (v == null) return '—';
		if (typeof v === 'object' && ('formatted' in v || 'value' in v)) {
			const c = v as Cell;
			return c.formatted ?? (c.value != null ? String(c.value) : '—');
		}
		return String(v);
	}

	function isMetric(col: AskColumn): boolean {
		return col.type === 'metric';
	}

	/** Human summary of the query the LLM generated: "<metrics> by <dimensions>". */
	function querySummary(q: unknown): string {
		if (!q || typeof q !== 'object') return '';
		const r = q as {
			rows?: { dataset: string; field: string }[];
			columns?: { dataset: string; field: string }[];
			values?: { metric: string }[];
		};
		const dims = [...(r.rows ?? []), ...(r.columns ?? [])].map((d) => d.field).join(', ');
		const metrics = (r.values ?? []).map((v) => v.metric).join(', ');
		if (!metrics && !dims) return '';
		if (!dims) return metrics;
		return `${metrics} by ${dims}`;
	}

	async function ask(q: string) {
		const trimmed = q.trim();
		if (!trimmed || busy || !configured) return;
		question = '';
		busy = true;
		// build history from prior successful turns
		const history = turns
			.filter((t) => !t.error)
			.flatMap((t) => [
				{ role: 'user' as const, content: t.question },
				{ role: 'assistant' as const, content: querySummary(t.queryUsed) || 'answered' }
			]);
		try {
			const r = await fetch(`${base}/api/ask`, {
				method: 'POST',
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({ question: trimmed, history })
			});
			const result = (await r.json()) as Turn;
			turns = [...turns, { ...result, question: trimmed }];
		} catch {
			turns = [
				...turns,
				{
					question: trimmed,
					model: null,
					queryUsed: null,
					columns: [],
					records: [],
					error: { code: 'NETWORK', message: 'Could not reach the ask service.' }
				}
			];
		} finally {
			busy = false;
		}
	}

	async function saveTurn(t: Turn, i: number) {
		if (!data.canSave || t.error) return;
		const title = t.question.length > 90 ? t.question.slice(0, 88) + '…' : t.question;
		const r = await fetch(`${base}/api/cases`, {
			method: 'POST',
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({
				title,
				kind: 'ask',
				note: querySummary(t.queryUsed),
				payload: { question: t.question, columns: t.columns, records: t.records.slice(0, 50), model: t.model }
			})
		});
		if (r.ok) turns = turns.map((x, xi) => (xi === i ? { ...x, saved: true } : x));
	}

	function onKey(e: KeyboardEvent) {
		if (e.key === 'Enter' && !e.shiftKey) {
			e.preventDefault();
			ask(question);
		}
	}
</script>

<div class="ask grid-bg">
	<div class="col">
		<header class="head">
			<div class="label">Natural language · over the Ossie model</div>
			<h1 class="disp">Ask Enigma</h1>
			<p class="sub">
				Ask in plain English. The model translates your question into an Ossie query, runs it over
				the Benafide warehouse through Saiku, and hands back real numbers — no MDX, no SQL.
			</p>
		</header>

		{#if !configured}
			<div class="notice mono">
				⚠ Ask isn't switched on yet — the Saiku backend has no AI provider configured
				({data.health.provider}). Everything else works; this lights up once a key is wired.
			</div>
		{/if}

		{#if turns.length === 0 && configured}
			<div class="suggest">
				<div class="suggest-label mono">Try</div>
				{#each data.suggestions as s}
					<button class="chip" onclick={() => ask(s)}>{s}</button>
				{/each}
			</div>
		{/if}

		<div class="thread">
			{#each turns as t, i}
				<div class="turn">
					<div class="q">
						<span class="who mono">you</span>
						<span class="q-text">{t.question}</span>
					</div>
					<div class="a">
						<span class="who mono ai">enigma</span>
						<div class="a-body">
							{#if t.error}
								<div class="err mono">
									{t.error.message}
									{#if t.error.available && t.error.available.length}
										<div class="avail">did you mean: {t.error.available.slice(0, 8).join(', ')}</div>
									{/if}
								</div>
							{:else}
								{#if querySummary(t.queryUsed)}
									<div class="trace mono">↳ {querySummary(t.queryUsed)}</div>
								{/if}
								{#if t.records.length > 0}
									<div class="table-wrap">
										<table>
											<thead>
												<tr>{#each t.columns as c}<th class:num={isMetric(c)}>{c.label}</th>{/each}</tr>
											</thead>
											<tbody>
												{#each t.records.slice(0, ROW_CAP) as rec}
													<tr>
														{#each t.columns as c}<td class:num={isMetric(c)} class:mono={isMetric(c)}>{cellText(rec[c.key])}</td>{/each}
													</tr>
												{/each}
											</tbody>
										</table>
										{#if t.records.length > ROW_CAP}
											<div class="more mono">+{t.records.length - ROW_CAP} more rows</div>
										{/if}
									</div>
								{:else}
									<div class="empty mono">No rows returned.</div>
								{/if}
								{#if data.canSave}
									<button class="save" disabled={t.saved} onclick={() => saveTurn(t, i)}>
										{t.saved ? '✓ Saved to cases' : '＋ Save to case'}
									</button>
								{/if}
							{/if}
						</div>
					</div>
				</div>
			{/each}
			{#if busy}
				<div class="turn"><div class="a"><span class="who mono ai">enigma</span><div class="thinking mono">thinking…</div></div></div>
			{/if}
		</div>

		<div class="composer">
			<textarea
				bind:value={question}
				onkeydown={onKey}
				disabled={!configured || busy}
				rows="1"
				placeholder={configured ? 'Ask about companies, owners, jurisdictions, risk…' : 'Ask is not configured yet'}
			></textarea>
			<button class="send" disabled={!configured || busy || !question.trim()} onclick={() => ask(question)}>Ask</button>
		</div>
	</div>
</div>

<style>
	.ask {
		min-height: calc(100vh - 56px);
		padding: clamp(20px, 4vw, 48px) 20px;
	}
	.col {
		max-width: 860px;
		margin: 0 auto;
		display: flex;
		flex-direction: column;
		gap: 18px;
	}
	.head {
		display: flex;
		flex-direction: column;
		gap: 8px;
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
	.notice {
		background: rgba(245, 181, 68, 0.1);
		border: 1px solid rgba(245, 181, 68, 0.4);
		color: var(--amber);
		border-radius: 10px;
		padding: 12px 16px;
		font-size: 12.5px;
		line-height: 1.5;
	}

	.suggest {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		align-items: center;
	}
	.suggest-label {
		color: var(--dim);
		font-size: 11px;
		text-transform: uppercase;
		letter-spacing: 1px;
	}
	.chip {
		background: var(--panel);
		border: 1px solid var(--line2);
		color: var(--fg);
		border-radius: 8px;
		padding: 8px 13px;
		font-size: 13px;
		cursor: pointer;
		text-align: left;
	}
	.chip:hover {
		border-color: var(--cyan);
		color: var(--cyan);
	}

	.thread {
		display: flex;
		flex-direction: column;
		gap: 20px;
	}
	.turn {
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.q,
	.a {
		display: flex;
		gap: 12px;
		align-items: flex-start;
	}
	.who {
		font-size: 10px;
		text-transform: uppercase;
		letter-spacing: 1px;
		color: var(--dim);
		flex: none;
		width: 52px;
		padding-top: 3px;
	}
	.who.ai {
		color: var(--cyan);
	}
	.q-text {
		font-size: 15px;
		color: var(--fg);
	}
	.a-body {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.trace {
		color: var(--muted);
		font-size: 12px;
	}
	.err {
		background: rgba(255, 93, 108, 0.1);
		border: 1px solid rgba(255, 93, 108, 0.35);
		color: var(--red);
		border-radius: 8px;
		padding: 10px 13px;
		font-size: 12.5px;
	}
	.avail {
		margin-top: 5px;
		color: var(--muted);
	}

	.table-wrap {
		border: 1px solid var(--line2);
		border-radius: 10px;
		overflow: hidden;
		background: var(--panel);
	}
	table {
		width: 100%;
		border-collapse: collapse;
		font-size: 13px;
	}
	th {
		text-align: left;
		padding: 9px 14px;
		color: var(--muted);
		font-size: 11px;
		text-transform: uppercase;
		letter-spacing: 0.5px;
		border-bottom: 1px solid var(--line2);
		background: rgba(255, 255, 255, 0.02);
	}
	td {
		padding: 8px 14px;
		border-bottom: 1px solid var(--line);
		color: var(--fg);
	}
	tbody tr:last-child td {
		border-bottom: none;
	}
	.num {
		text-align: right;
	}
	td.num {
		color: var(--cyan);
	}
	.more {
		padding: 7px 14px;
		color: var(--dim);
		font-size: 11px;
		border-top: 1px solid var(--line);
	}
	.empty {
		color: var(--muted);
		font-size: 13px;
	}
	.thinking {
		color: var(--muted);
		font-size: 13px;
		padding-top: 3px;
	}
	.save {
		align-self: flex-start;
		background: transparent;
		border: 1px solid var(--line2);
		color: var(--muted);
		border-radius: 7px;
		padding: 6px 12px;
		font-size: 12px;
		cursor: pointer;
	}
	.save:hover:not(:disabled) {
		border-color: var(--cyan);
		color: var(--cyan);
	}
	.save:disabled {
		color: var(--accent-2, #5fe0a0);
		border-color: rgba(95, 224, 160, 0.4);
		cursor: default;
	}

	.composer {
		position: sticky;
		bottom: 16px;
		display: flex;
		gap: 10px;
		background: var(--panel);
		border: 1px solid var(--line2);
		border-radius: 12px;
		padding: 10px 12px;
	}
	textarea {
		flex: 1;
		background: transparent;
		border: none;
		color: var(--fg);
		font-size: 14px;
		font-family: inherit;
		resize: none;
		outline: none;
		padding: 6px 4px;
		max-height: 120px;
	}
	.send {
		background: var(--cyan);
		color: var(--bg);
		border: none;
		border-radius: 8px;
		padding: 0 20px;
		font-weight: 600;
		font-size: 14px;
		cursor: pointer;
	}
	.send:disabled {
		opacity: 0.4;
		cursor: default;
	}
</style>
