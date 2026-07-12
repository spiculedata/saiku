<script lang="ts">
	import { goto } from '$app/navigation';
	import { base } from '$app/paths';
	import type { SearchResult } from '$lib/types';

	const MIN_QUERY_LENGTH = 2;
	const DEBOUNCE_MS = 220;

	let q = $state('');
	let results = $state<SearchResult[]>([]);
	let timer: ReturnType<typeof setTimeout>;

	function onInput() {
		clearTimeout(timer);
		timer = setTimeout(async () => {
			if (q.trim().length < MIN_QUERY_LENGTH) {
				results = [];
				return;
			}
			const r = await fetch(`${base}/api/entities?q=${encodeURIComponent(q)}`);
			results = r.ok ? await r.json() : [];
		}, DEBOUNCE_MS);
	}
</script>

<div class="wrap">
	<input class="input mono" bind:value={q} oninput={onInput} placeholder="Search a company or person…" />
	{#if results.length}
		<ul class="results">
			{#each results as e (e.id)}
				<li>
					<button onclick={() => goto(`${base}/e/${encodeURIComponent(e.id)}`)}>
						<b>{e.name}</b>
						<span class="mono">{e.jurisdiction ?? ''} · {e.status ?? ''}</span>
					</button>
				</li>
			{/each}
		</ul>
	{/if}
</div>

<style>
	.wrap {
		position: relative;
		max-width: 640px;
		margin: 0 auto;
	}
	.input {
		width: 100%;
		background: var(--panel);
		border: 1px solid var(--line2);
		border-radius: 12px;
		padding: 16px 18px;
		color: var(--fg);
		font-size: 16px;
	}
	.results {
		position: absolute;
		left: 0;
		right: 0;
		margin-top: 6px;
		background: var(--panel2);
		border: 1px solid var(--line2);
		border-radius: 12px;
		overflow: hidden;
		list-style: none;
		z-index: 5;
	}
	.results button {
		width: 100%;
		display: flex;
		justify-content: space-between;
		gap: 12px;
		align-items: center;
		padding: 12px 16px;
		background: transparent;
		border: 0;
		color: var(--fg);
		cursor: pointer;
		text-align: left;
	}
	.results button:hover {
		background: var(--panel);
	}
	.results span {
		color: var(--muted);
		font-size: 12px;
	}
</style>
