<script lang="ts">
	/**
	 * Import a vendor semantic model (LookML, dbt) into Saiku — saiku#1730.
	 *
	 * Three steps in one dialog, because they're one decision:
	 *
	 *   1. pick a format + attach the export (files or pasted text) → **Convert**
	 *   2. read the preview: element counts, the per-dataset table, and the validation
	 *      report (errors / warnings / info, per element) → **Save & register**
	 *   3. save writes the YAML under the server's model directory and hands the path back,
	 *      which the caller drops into the OSSIE datasource form.
	 *
	 * Nothing is persisted before step 3, so an import that comes out badly can be
	 * abandoned with no trace. Only the *datasource* is created by the caller — this modal
	 * never registers one itself, because that's the existing admin datasource API's job
	 * and duplicating it would give two ways to create an OSSIE connection.
	 */
	import Modal from '$lib/components/Modal.svelte';
	import { Button, Input, Select, Textarea } from '$lib/components/ui';
	import { FeedbackBanner, FormField } from '$lib/design-system';
	import {
		importOssieModel,
		listOssieImportFormats,
		saveOssieImport,
		type OssieImportFormat,
		type OssieImportResult
	} from '$lib/api/ossie';
	import { toasts } from '$lib/stores/toasts.svelte';

	interface Props {
		open: boolean;
		/**
		 * Called with the path the server wrote the model to. The caller opens the
		 * new-datasource form pre-filled with it (type OSSIE, YAML path, model name) and
		 * the operator supplies the warehouse JDBC URL there.
		 */
		onRegistered: (path: string, modelName: string) => void;
		onCancel: () => void;
	}

	let { open, onRegistered, onCancel }: Props = $props();

	type Step = 'pick' | 'preview';

	let step = $state<Step>('pick');
	let formats = $state<OssieImportFormat[]>([]);
	let format = $state('');
	let formatsError = $state<string | null>(null);
	let files = $state<File[]>([]);
	let pasted = $state('');
	let modelName = $state('');
	let busy = $state(false);
	let error = $state<string | null>(null);
	let result = $state<OssieImportResult | null>(null);
	let savedPath = $state<string | null>(null);

	// The server is the source of truth for what's importable — the picker never
	// hard-codes a format list, so a new spoke ships without a UI change.
	$effect(() => {
		if (!open) return;
		void loadFormats();
	});

	async function loadFormats() {
		formatsError = null;
		try {
			formats = await listOssieImportFormats();
			if (formats.length > 0 && !formats.some((f) => f.id === format)) {
				format = formats[0].id;
			}
		} catch (e) {
			formats = [];
			formatsError = e instanceof Error ? e.message : String(e);
		}
	}

	const selected = $derived(formats.find((f) => f.id === format) ?? null);
	const canConvert = $derived(!!format && !busy && (files.length > 0 || pasted.trim().length > 0));

	/** Read every picked file locally so the request body stays plain JSON. */
	async function readFiles(picked: File[]): Promise<{ name: string; content: string }[]> {
		return Promise.all(picked.map(async (f) => ({ name: f.name, content: await f.text() })));
	}

	async function convert() {
		if (!canConvert) return;
		busy = true;
		error = null;
		try {
			const payload =
				files.length > 0 ? await readFiles(files) : [{ name: 'pasted', content: pasted }];
			result = await importOssieModel({
				format,
				modelName: modelName.trim() || undefined,
				files: payload
			});
			step = 'preview';
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			busy = false;
		}
	}

	async function save() {
		if (!result || busy) return;
		busy = true;
		error = null;
		try {
			const saved = await saveOssieImport({
				modelName: result.modelName,
				yaml: result.yaml,
				overwrite: true
			});
			savedPath = saved.path;
			toasts.success('Model saved', saved.path);
			onRegistered(saved.path, result.modelName);
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			busy = false;
		}
	}

	function reset() {
		step = 'pick';
		files = [];
		pasted = '';
		modelName = '';
		error = null;
		result = null;
		savedPath = null;
	}

	function onFileList(e: Event) {
		const input = e.currentTarget as HTMLInputElement;
		files = Array.from(input.files ?? []);
	}

	function onClose() {
		reset();
		onCancel();
	}
</script>

<Modal title="Import a vendor semantic model" {open} size="xl" {onClose}>
	{#if step === 'pick'}
		<p class="intro">
			Walk in with the model you already have — a Looker LookML project or a dbt manifest. Saiku
			converts it to its own semantic YAML, validates it, and only writes anything once you confirm.
		</p>

		{#if formatsError}
			<FeedbackBanner tone="error" testid="ossie-import-formats-error">
				Couldn't load the import formats: {formatsError}
			</FeedbackBanner>
		{:else if formats.length === 0}
			<p class="muted">Loading formats...</p>
		{:else}
			<FormField label="Source format" hint={selected?.description ?? ''}>
				<Select bind:value={format}>
					{#each formats as f (f.id)}
						<option value={f.id}>{f.displayName}</option>
					{/each}
				</Select>
			</FormField>

			<FormField
				label="Model name"
				hint="Optional. Defaults to the first explore or view in the upload."
			>
				<Input bind:value={modelName} placeholder="Sales" />
			</FormField>

			<FormField label="Files" hint={selected?.fileExtensions.join(', ') ?? ''}>
				<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
				<input
					type="file"
					multiple
					accept={selected?.fileExtensions.join(',')}
					onchange={onFileList}
					data-testid="ossie-import-files"
				/>
			</FormField>

			{#if files.length > 0}
				<ul class="filelist">
					{#each files as f (f.name)}
						<li>{f.name}</li>
					{/each}
				</ul>
			{/if}

			<details class="paste">
				<summary>...or paste the model</summary>
				<Textarea rows={8} bind:value={pasted} placeholder="view: orders" />
			</details>
		{/if}

		{#if error}
			<FeedbackBanner tone="error" testid="ossie-import-error">{error}</FeedbackBanner>
		{/if}
	{:else if result}
		<div class="summary">
			<div class="stat">
				<span class="stat__value">{result.validation.datasetCount}</span>
				<span class="stat__label">datasets</span>
			</div>
			<div class="stat">
				<span class="stat__value">{result.validation.fieldCount}</span>
				<span class="stat__label">fields</span>
			</div>
			<div class="stat">
				<span class="stat__value">{result.validation.metricCount}</span>
				<span class="stat__label">metrics</span>
			</div>
			<div class="stat">
				<span class="stat__value">{result.validation.relationshipCount}</span>
				<span class="stat__label">relationships</span>
			</div>
			<div class="stat">
				<span class="stat__value">{result.modelName}</span>
				<span class="stat__label">model</span>
			</div>
		</div>

		{#if result.validation.datasets.length > 0}
			<table class="data-grid" data-testid="ossie-import-datasets">
				<thead>
					<tr><th>Dataset</th><th>Source table</th><th>Fields</th><th>Keys</th></tr>
				</thead>
				<tbody>
					{#each result.validation.datasets as ds (ds.name)}
						<tr>
							<td>{ds.name}</td>
							<td>{ds.source ?? '—'}</td>
							<td>{ds.fieldCount}</td>
							<td>{ds.primaryKeyCount}</td>
						</tr>
					{/each}
				</tbody>
			</table>
		{/if}

		<details class="yaml" open={result.validation.errorCount > 0}>
			<summary>Generated Ossie YAML</summary>
			<pre>{result.yaml}</pre>
		</details>

		<section class="report" data-testid="ossie-import-report">
			<h3>
				Validation report
				<span class="counts">
					{result.validation.errorCount} errors ·
					{result.validation.warningCount} warnings ·
					{result.validation.infoCount} notes
				</span>
			</h3>
			{#if result.validation.diagnostics.length === 0}
				<p class="muted">Nothing to report — every element converted cleanly.</p>
			{:else}
				<ul>
					{#each result.validation.diagnostics as d, i (i)}
						<li class="diag diag--{d.severity.toLowerCase()}">
							<span class="diag__severity">{d.severity}</span>
							{#if d.element}<code class="diag__element">{d.element}</code>{/if}
							<span class="diag__message">{d.message}</span>
						</li>
					{/each}
				</ul>
			{/if}
		</section>

		{#if result.validation.errorCount > 0}
			<FeedbackBanner tone="error" testid="ossie-import-errors">
				This model has {result.validation.errorCount} error(s) and can't be saved yet — an error means
				an element the query path can't use (a join to a table that isn't there, a duplicated name). Re-import
				without it, or edit the YAML.
			</FeedbackBanner>
		{/if}

		{#if error}
			<FeedbackBanner tone="error" testid="ossie-import-error">{error}</FeedbackBanner>
		{/if}
	{/if}

	{#snippet footer()}
		<Button variant="outline" onclick={onClose}>Cancel</Button>
		{#if step === 'pick'}
			<Button disabled={!canConvert} onclick={convert} data-testid="ossie-import-convert">
				{busy ? 'Converting...' : 'Convert'}
			</Button>
		{:else if result}
			<Button variant="outline" onclick={() => (step = 'pick')} disabled={busy}>Back</Button>
			<Button
				disabled={busy || result.validation.errorCount > 0}
				onclick={save}
				data-testid="ossie-import-save"
			>
				{busy ? 'Saving...' : savedPath ? 'Saved' : 'Save & register'}
			</Button>
		{/if}
	{/snippet}
</Modal>

<style>
	.intro {
		margin: 0 0 var(--space-4);
		color: hsl(var(--fg-muted));
		font-size: var(--fs-sm);
	}
	.muted {
		color: hsl(var(--fg-muted));
		font-size: var(--fs-sm);
	}
	.filelist {
		margin: 0 0 var(--space-3);
		padding-left: var(--space-4);
		font-size: var(--fs-sm);
		color: hsl(var(--fg-muted));
	}
	.paste,
	.yaml {
		margin: var(--space-3) 0;
	}
	.paste summary,
	.yaml summary {
		cursor: pointer;
		font-size: var(--fs-sm);
		color: hsl(var(--fg-muted));
	}
	.yaml pre {
		max-height: 260px;
		overflow: auto;
		margin-top: var(--space-2);
		padding: var(--space-2);
		background: hsl(var(--bg-subtle));
		border-radius: var(--radius-sm);
		font-size: var(--fs-xs);
	}
	.summary {
		display: flex;
		gap: var(--space-5);
		flex-wrap: wrap;
		margin-bottom: var(--space-3);
	}
	.stat {
		display: flex;
		flex-direction: column;
	}
	.stat__value {
		font-size: var(--fs-lg);
		font-weight: 600;
	}
	.stat__label {
		font-size: var(--fs-xs);
		color: hsl(var(--fg-muted));
	}
	.report h3 {
		display: flex;
		align-items: baseline;
		gap: var(--space-2);
		margin: var(--space-4) 0 var(--space-2);
		font-size: var(--fs-sm);
	}
	.counts {
		font-weight: 400;
		font-size: var(--fs-xs);
		color: hsl(var(--fg-muted));
	}
	.report ul {
		margin: 0;
		padding: 0;
		list-style: none;
		max-height: 320px;
		overflow: auto;
	}
	.diag {
		display: flex;
		gap: var(--space-2);
		align-items: baseline;
		padding: 3px 0;
		font-size: var(--fs-xs);
		border-bottom: 1px solid hsl(var(--border-subtle));
	}
	.diag__severity {
		font-weight: 600;
		min-width: 62px;
	}
	.diag--error .diag__severity {
		color: hsl(var(--danger));
	}
	.diag--warning .diag__severity {
		color: hsl(var(--warning));
	}
	.diag--info .diag__severity {
		color: hsl(var(--fg-muted));
	}
	.diag__element {
		color: hsl(var(--fg-muted));
	}
	.diag__message {
		flex: 1;
	}
</style>
