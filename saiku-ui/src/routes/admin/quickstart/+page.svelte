<script lang="ts">
	/*
	 * Quickstart CSV-upload page (saiku#1117).
	 *
	 * "CSV/Parquet upload → starter-cube UI route" — Phase 1 (CSV) of the issue's phasing. Picks up
	 * a CSV file, loads it into a fresh H2 table (new: `QuickstartResource`), registers it as a
	 * Saiku datasource and runs the existing schema-generator pipeline against it
	 * (`SchemaGeneratorResource`, already shipped), then — unlike the admin schema-generator page,
	 * which stops for the operator to review suggestions — saves automatically and jumps straight
	 * into the workspace via the existing `starterCube.ts` URL contract. No review step: the whole
	 * point of "quickstart" is a self-service path from "I have a CSV" to a populated query model.
	 */
	import { onDestroy } from 'svelte';
	import { goto } from '$app/navigation';

	import { uploadCsv } from '$lib/api/quickstart';
	import { adminDatasources } from '$lib/api/admin';
	import { createSchemaGenClient } from '$lib/api/schemaGen';
	import { createSchemaGenStore } from '$lib/stores/schemaGen.svelte';
	import { buildLaunchUrl } from '$lib/api/starterCube';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { Button, Input } from '$lib/components/ui';
	import { FeedbackBanner } from '$lib/design-system';

	import {
		buildDatasourcePayload,
		defaultTableNameFromFileName,
		isFailed,
		isReadyToSave,
		stageLabel
	} from './pageViewModel';

	const client = createSchemaGenClient();
	const store = createSchemaGenStore(client);

	type Phase = 'idle' | 'uploading' | 'generating' | 'saving' | 'error';

	let file = $state<File | null>(null);
	let tableName = $state('');
	let phase = $state<Phase>('idle');
	let errorMessage = $state<string | null>(null);

	// Set once the datasource is registered; finishSave() needs both to build the launch URL.
	let connectionName = $state<string | null>(null);
	let chosenSchemaName = $state<string | null>(null);

	const busy = $derived(phase === 'uploading' || phase === 'generating' || phase === 'saving');
	const canSubmit = $derived(file !== null && !busy);

	function handleFileChange(e: Event) {
		const input = e.currentTarget as HTMLInputElement;
		const picked = input.files?.[0] ?? null;
		file = picked;
		if (picked !== null && tableName.trim() === '') {
			tableName = defaultTableNameFromFileName(picked.name);
		}
	}

	async function startQuickstart() {
		if (file === null) return;
		phase = 'uploading';
		errorMessage = null;
		try {
			const upload = await uploadCsv(file, tableName.trim() || undefined);
			// The server is the source of truth for the resolved name — a collision or extra
			// sanitising can change it from what was typed.
			chosenSchemaName = upload.tableName;
			const payload = buildDatasourcePayload(upload, upload.tableName);
			const created = await adminDatasources.create(payload);
			if (created === null) {
				throw new Error('the server accepted the upload but did not return a datasource');
			}
			connectionName = created.connectionName ?? created.name;

			phase = 'generating';
			await store.start(created.id);
			if (store.error !== null) {
				throw new Error(store.error);
			}
		} catch (err) {
			phase = 'error';
			errorMessage = err instanceof Error ? err.message : String(err);
		}
	}

	async function finishSave() {
		if (chosenSchemaName === null) return;
		await store.save(chosenSchemaName);
		if (store.error !== null) {
			phase = 'error';
			errorMessage = store.error;
			return;
		}
		const cube = store.draft?.cubes[0];
		if (!cube || connectionName === null) {
			phase = 'error';
			errorMessage = 'the schema saved, but no cube was generated from it';
			return;
		}
		toasts.success(`Generated "${cube.name}" from ${file?.name ?? 'your CSV'}`);
		const url = buildLaunchUrl({
			connection: connectionName,
			schema: chosenSchemaName,
			cube: cube.name
		});
		await goto(url);
	}

	// Drives the pipeline forward once the schema-generator store reports a terminal stage —
	// gated on `phase === 'generating'` so it fires exactly once per upload, not on every poll.
	$effect(() => {
		if (phase !== 'generating') return;
		if (isFailed(store.stage)) {
			phase = 'error';
			errorMessage = store.failureMessage ?? 'schema generation failed';
		} else if (isReadyToSave(store.stage)) {
			phase = 'saving';
			void finishSave();
		}
	});

	function retry() {
		phase = 'idle';
		errorMessage = null;
		store.stop();
	}

	onDestroy(() => {
		store.stop();
	});
</script>

<div class="quickstart">
	<div class="quickstart__card">
		<h1>Quickstart</h1>
		<p class="quickstart__intro">
			Upload a CSV and Saiku will build a starter cube from it — no schema to write by hand.
		</p>

		{#if phase === 'error'}
			<FeedbackBanner tone="error" size="sm">{errorMessage}</FeedbackBanner>
			<Button size="sm" variant="ghost" onclick={retry}>Try again</Button>
		{:else if busy}
			<FeedbackBanner tone="info" size="sm"
				>{stageLabel(store.sessionId === null ? null : store.stage)}</FeedbackBanner
			>
		{:else}
			<label class="quickstart__field">
				<span>CSV file</span>
				<input type="file" accept=".csv,text/csv" onchange={handleFileChange} />
			</label>

			<label class="quickstart__field">
				<span>Cube name</span>
				<Input bind:value={tableName} placeholder="e.g. sales" />
			</label>

			<Button disabled={!canSubmit} onclick={startQuickstart}>Generate cube</Button>
		{/if}
	</div>
</div>

<style>
	.quickstart {
		display: flex;
		justify-content: center;
		padding: var(--space-8, 2rem) var(--space-4, 1rem);
	}
	.quickstart__card {
		display: flex;
		flex-direction: column;
		gap: var(--space-4, 1rem);
		width: min(480px, 100%);
		padding: var(--space-6, 1.5rem);
		border: 1px solid hsl(var(--border));
		border-radius: var(--radius-md, 8px);
		background: hsl(var(--bg));
	}
	.quickstart__card h1 {
		margin: 0;
		font-size: var(--fs-lg);
		font-weight: var(--weight-semibold);
	}
	.quickstart__intro {
		margin: 0;
		color: hsl(var(--fg-muted));
		font-size: var(--fs-sm);
	}
	.quickstart__field {
		display: flex;
		flex-direction: column;
		gap: var(--space-1, 0.25rem);
		font-size: var(--fs-sm);
	}
</style>
