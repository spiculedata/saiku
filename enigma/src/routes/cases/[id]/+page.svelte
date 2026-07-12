<script lang="ts">
	import { base } from '$app/paths';
	import { goto, invalidateAll } from '$app/navigation';
	import type { PageData } from './$types';
	import { STATUS_META, PRIORITY_META, STATUS_ORDER, activityMeta } from '$lib/caseMeta';
	import { CASE_PRIORITIES, type CaseStatus, type CasePriority } from '$lib/caseTypes';

	let { data }: { data: PageData } = $props();

	let noteDraft = $state('');
	let assigneeDraft = $state(data.case.assignee ?? '');
	let busy = $state(false);
	// keep the assignee input in sync when the case reloads
	$effect(() => {
		assigneeDraft = data.case.assignee ?? '';
	});

	function when(iso: string): string {
		return new Date(iso).toLocaleString('en-GB', {
			day: '2-digit',
			month: 'short',
			hour: '2-digit',
			minute: '2-digit'
		});
	}

	async function patch(body: Record<string, unknown>) {
		busy = true;
		try {
			await fetch(`${base}/api/cases/${data.case.id}`, {
				method: 'PATCH',
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify(body)
			});
			await invalidateAll();
		} finally {
			busy = false;
		}
	}

	function setStatus(s: CaseStatus) {
		if (s !== data.case.status) patch({ status: s });
	}
	function setPriority(p: CasePriority) {
		if (p !== data.case.priority) patch({ priority: p });
	}
	function saveAssignee() {
		const next = assigneeDraft.trim();
		if (next !== (data.case.assignee ?? '')) patch({ assignee: next || null });
	}

	async function addNote() {
		const text = noteDraft.trim();
		if (!text || busy) return;
		busy = true;
		try {
			await fetch(`${base}/api/cases/${data.case.id}/notes`, {
				method: 'POST',
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({ body: text })
			});
			noteDraft = '';
			await invalidateAll();
		} finally {
			busy = false;
		}
	}

	async function remove() {
		if (busy) return;
		busy = true;
		const r = await fetch(`${base}/api/cases/${data.case.id}`, { method: 'DELETE' });
		if (r.ok) await goto(`${base}/cases`);
		else busy = false;
	}

	function parseAskAnswer(answer: string | null): { cols: string[]; rows: string[][] } | null {
		if (!answer || answer.startsWith('ERROR:')) return null;
		try {
			const p = JSON.parse(answer) as {
				columns?: { key: string; label: string; type: string }[];
				records?: Record<string, unknown>[];
			};
			if (!p.columns?.length) return null;
			const cols = p.columns.map((c) => c.label);
			const rows = (p.records ?? []).slice(0, 4).map((rec) =>
				p.columns!.map((c) => {
					const v = rec[c.key];
					if (v && typeof v === 'object' && 'formatted' in v) return String((v as { formatted: string }).formatted);
					return v == null ? '—' : String(v);
				})
			);
			return { cols, rows };
		} catch {
			return null;
		}
	}
</script>

<div class="detail grid-bg">
	<a class="back mono" href="{base}/cases">← Cases</a>

	<div class="grid">
		<main class="main">
			<header class="head">
				<div class="row1">
					<span class="kind mono" data-kind={data.case.kind}>{data.case.kind}</span>
					<span class="created mono">opened {when(data.case.createdAt)}</span>
				</div>
				<h1 class="disp">{data.case.kind === 'ask' ? data.case.title : (data.case.subjectName ?? data.case.title)}</h1>
				<div class="statusbar">
					{#each STATUS_ORDER as s}
						<button
							class="stbtn"
							class:on={data.case.status === s}
							style="--tone:{STATUS_META[s].color}"
							disabled={busy}
							onclick={() => setStatus(s)}
						>
							{STATUS_META[s].label}
						</button>
					{/each}
				</div>
			</header>

			{#if data.case.kind !== 'ask' && data.case.subjectId}
				<section class="snapshot">
					<div class="snap-main">
						<div class="snap-name">{data.case.subjectName ?? data.case.subjectId}</div>
						<div class="snap-meta mono">
							{#if data.case.jurisdiction}<span class="jur">{data.case.jurisdiction}</span> · {/if}{data.case.subjectId}
						</div>
						{#if data.case.note}<div class="snap-note mono">{data.case.note}</div>{/if}
					</div>
					<a class="snap-link" href="{base}/e/{data.case.subjectId}">Open investigation →</a>
				</section>
			{/if}

			<section class="notes-box">
				<textarea
					bind:value={noteDraft}
					disabled={busy}
					rows="2"
					placeholder="Add a note to the case record…"
				></textarea>
				<button class="add" disabled={busy || !noteDraft.trim()} onclick={addNote}>Add note</button>
			</section>

			<section class="timeline">
				<div class="tl-head mono">Activity</div>
				<ol>
					{#each data.activities as a (a.id)}
						{@const m = activityMeta(a.kind)}
						<li class="tl-item" class:note={a.kind === 'note'}>
							<span class="tl-icon" aria-hidden="true">{m.icon}</span>
							<div class="tl-body">
								{#if a.kind === 'note'}
									<div class="tl-note">{a.detail}</div>
								{:else}
									<div class="tl-line"><b>{m.label}</b>{#if a.detail} — {a.detail}{/if}</div>
								{/if}
								<div class="tl-when mono">{a.actor ?? 'system'} · {when(a.createdAt)}</div>
							</div>
						</li>
					{/each}
				</ol>
			</section>
		</main>

		<aside class="rail">
			<div class="panel">
				<div class="panel-h mono">Priority</div>
				<div class="prio-row">
					{#each CASE_PRIORITIES as p}
						<button
							class="prio-btn"
							class:on={data.case.priority === p}
							style="--tone:{PRIORITY_META[p].color}"
							disabled={busy}
							onclick={() => setPriority(p)}
						>
							{PRIORITY_META[p].label}
						</button>
					{/each}
				</div>
			</div>

			<div class="panel">
				<div class="panel-h mono">Assignee</div>
				<div class="assign-row">
					<input
						class="mono"
						bind:value={assigneeDraft}
						disabled={busy}
						placeholder="unassigned"
						onkeydown={(e) => e.key === 'Enter' && saveAssignee()}
						onblur={saveAssignee}
					/>
				</div>
			</div>

			{#if data.threads.length}
				<div class="panel">
					<div class="panel-h mono">Ask threads · {data.threads.length}</div>
					<ul class="threads">
						{#each data.threads as t (t.id)}
							{@const res = parseAskAnswer(t.answer)}
							<li>
								<div class="th-q">{t.question}</div>
								{#if res}
									<div class="th-table mono">
										{#each res.rows as r}
											<div class="th-row"><span>{r[0]}</span><span class="th-v">{r[res.cols.length - 1]}</span></div>
										{/each}
									</div>
								{:else if t.answer?.startsWith('ERROR:')}
									<div class="th-err mono">query failed</div>
								{/if}
							</li>
						{/each}
					</ul>
				</div>
			{/if}

			<button class="danger" disabled={busy} onclick={remove}>Delete case</button>
		</aside>
	</div>
</div>

<style>
	.detail {
		min-height: calc(100vh - 56px);
		padding: clamp(18px, 4vw, 44px);
	}
	.back {
		display: inline-block;
		color: var(--muted);
		font-size: 12px;
		margin-bottom: 18px;
	}
	.back:hover {
		color: var(--cyan);
	}
	.grid {
		display: grid;
		grid-template-columns: 1fr 300px;
		gap: 20px;
		align-items: start;
	}

	.head {
		margin-bottom: 18px;
	}
	.row1 {
		display: flex;
		align-items: center;
		gap: 12px;
		margin-bottom: 10px;
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
	.created {
		font-size: 11px;
		color: var(--dim);
	}
	.head h1 {
		font-weight: 500;
		font-size: clamp(22px, 3vw, 32px);
		letter-spacing: -0.5px;
		line-height: 1.15;
		margin-bottom: 16px;
	}
	.statusbar {
		display: inline-flex;
		background: var(--panel);
		border: 1px solid var(--line2);
		border-radius: 9px;
		padding: 3px;
		gap: 2px;
	}
	.stbtn {
		background: transparent;
		border: none;
		color: var(--muted);
		font-size: 12px;
		padding: 7px 14px;
		border-radius: 7px;
		cursor: pointer;
	}
	.stbtn:hover:not(:disabled) {
		color: var(--fg);
	}
	.stbtn.on {
		background: color-mix(in srgb, var(--tone) 18%, transparent);
		color: var(--tone);
		font-weight: 600;
	}

	.snapshot {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 14px;
		background: var(--panel);
		border: 1px solid var(--line2);
		border-radius: 12px;
		padding: 16px 18px;
		margin-bottom: 16px;
	}
	.snap-name {
		font-size: 15px;
		color: var(--fg);
	}
	.snap-meta {
		font-size: 11px;
		color: var(--muted);
		margin-top: 4px;
	}
	.snap-meta .jur {
		color: var(--amber);
	}
	.snap-note {
		font-size: 11px;
		color: var(--dim);
		margin-top: 4px;
	}
	.snap-link {
		flex: none;
		color: var(--cyan);
		font-size: 13px;
	}

	.notes-box {
		display: flex;
		gap: 10px;
		background: var(--panel);
		border: 1px solid var(--line2);
		border-radius: 12px;
		padding: 12px;
		margin-bottom: 18px;
	}
	.notes-box textarea {
		flex: 1;
		background: transparent;
		border: none;
		outline: none;
		resize: none;
		color: var(--fg);
		font-family: inherit;
		font-size: 13.5px;
		padding: 6px;
	}
	.add {
		align-self: flex-end;
		background: var(--cyan);
		color: var(--bg);
		border: none;
		border-radius: 8px;
		padding: 8px 16px;
		font-weight: 600;
		font-size: 13px;
		cursor: pointer;
	}
	.add:disabled {
		opacity: 0.4;
		cursor: default;
	}

	.tl-head,
	.panel-h {
		font-size: 10px;
		text-transform: uppercase;
		letter-spacing: 1px;
		color: var(--dim);
		margin-bottom: 12px;
	}
	.timeline ol {
		list-style: none;
		display: flex;
		flex-direction: column;
		gap: 2px;
	}
	.tl-item {
		display: flex;
		gap: 12px;
		padding: 10px 0 10px 2px;
		border-left: 1px solid var(--line2);
		margin-left: 9px;
		padding-left: 18px;
		position: relative;
	}
	.tl-icon {
		position: absolute;
		left: -9px;
		top: 9px;
		width: 18px;
		height: 18px;
		background: var(--panel2);
		border: 1px solid var(--line2);
		border-radius: 50%;
		font-size: 9px;
		display: flex;
		align-items: center;
		justify-content: center;
		color: var(--muted);
	}
	.tl-item.note .tl-note {
		background: var(--panel);
		border: 1px solid var(--line2);
		border-radius: 8px;
		padding: 9px 12px;
		font-size: 13px;
		color: var(--fg);
		line-height: 1.45;
		white-space: pre-wrap;
	}
	.tl-line {
		font-size: 13px;
		color: var(--muted);
	}
	.tl-line b {
		color: var(--fg);
		font-weight: 600;
	}
	.tl-when {
		font-size: 10px;
		color: var(--dim);
		margin-top: 4px;
	}

	.rail {
		display: flex;
		flex-direction: column;
		gap: 14px;
	}
	.panel {
		background: var(--panel);
		border: 1px solid var(--line2);
		border-radius: 12px;
		padding: 14px 16px;
	}
	.prio-row {
		display: flex;
		gap: 6px;
	}
	.prio-btn {
		flex: 1;
		background: transparent;
		border: 1px solid var(--line2);
		color: var(--muted);
		border-radius: 7px;
		padding: 7px 0;
		font-size: 12px;
		cursor: pointer;
	}
	.prio-btn.on {
		border-color: var(--tone);
		color: var(--tone);
		font-weight: 600;
	}
	.assign-row input {
		width: 100%;
		background: var(--bg);
		border: 1px solid var(--line2);
		border-radius: 7px;
		padding: 8px 10px;
		color: var(--fg);
		font-size: 13px;
		outline: none;
	}
	.assign-row input:focus {
		border-color: var(--cyan);
	}
	.threads {
		list-style: none;
		display: flex;
		flex-direction: column;
		gap: 12px;
	}
	.th-q {
		font-size: 12.5px;
		color: var(--fg);
		line-height: 1.4;
		margin-bottom: 6px;
	}
	.th-table {
		display: flex;
		flex-direction: column;
		gap: 3px;
	}
	.th-row {
		display: flex;
		justify-content: space-between;
		gap: 10px;
		font-size: 11px;
		color: var(--muted);
	}
	.th-row .th-v {
		color: var(--cyan);
	}
	.th-err {
		font-size: 11px;
		color: var(--red);
	}
	.danger {
		background: transparent;
		border: 1px solid color-mix(in srgb, var(--red) 40%, transparent);
		color: var(--red);
		border-radius: 9px;
		padding: 10px;
		font-size: 13px;
		cursor: pointer;
	}
	.danger:hover:not(:disabled) {
		background: rgba(255, 93, 108, 0.1);
	}

	@media (max-width: 820px) {
		.grid {
			grid-template-columns: 1fr;
		}
	}
</style>
