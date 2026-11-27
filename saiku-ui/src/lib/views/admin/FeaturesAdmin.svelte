<script lang="ts">
	/*
	 * Feature toggles admin (saiku#2618). Per-role on/off switches for the main UI
	 * features (Dashboards, Apps, Notebooks, the MDX workbench, the SQL workbench).
	 * Grant semantics are restriction-only: "Everyone" (unrestricted), "Admins only",
	 * or selected roles + admins. The server enforces the denial on each feature's
	 * REST prefixes (FeatureGateFilter), so the denial holds even for a caller with a
	 * direct link; the admin panel itself is never feature-gated. Every change is
	 * written to the feature-toggle audit trail (shown at the bottom).
	 */
	import { onMount } from 'svelte';
	import { Button } from '$lib/components/ui';
	import { adminFeatures, type AdminFeature, type FeatureAuditEntry } from '$lib/api/admin';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import { Skeleton } from '$lib/design-system';

	/** How a feature is granted — mirrors the server's null / empty / listed triad. */
	type Mode = 'everyone' | 'admins' | 'roles';

	let features = $state<AdminFeature[]>([]);
	let knownRoles = $state<string[]>([]);
	let loading = $state(true);
	let error = $state<string | null>(null);
	let saving = $state<string | null>(null);
	let audit = $state<FeatureAuditEntry[]>([]);
	let auditOpen = $state(false);

	/** The grant a feature would get if saved now: null = unrestricted, [] = admins only, list = roles. */
	let grants = $state<Record<string, string[] | null>>({});

	async function refresh() {
		loading = true;
		error = null;
		try {
			const overview = await adminFeatures.overview();
			features = overview.features;
			knownRoles = overview.knownRoles;
			grants = Object.fromEntries(features.map((f) => [f.key, f.roles]));
			audit = await adminFeatures.audit().catch(() => []);
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			loading = false;
		}
	}
	onMount(() => void refresh());

	function modeOf(f: AdminFeature): Mode {
		const grant = grants[f.key];
		if (grant === null || grant === undefined) return 'everyone';
		if (grant.length === 0) return 'admins';
		return 'roles';
	}

	function setMode(f: AdminFeature, mode: Mode) {
		grants[f.key] = mode === 'everyone' ? null : mode === 'admins' ? [] : rolesFor(f, mode);
	}

	/** When switching into "roles" mode, default the grant to the current effective roles. */
	function rolesFor(f: AdminFeature, mode: Mode): string[] {
		const current = grants[f.key];
		if (mode !== 'roles') return [];
		if (current === null || current === undefined) return [];
		return [...current];
	}

	function toggleRole(f: AdminFeature, role: string) {
		const current = grants[f.key];
		const list = current === null || current === undefined ? [] : [...current];
		grants[f.key] = list.includes(role) ? list.filter((r) => r !== role) : [...list, role];
	}

	function dirty(f: AdminFeature): boolean {
		const current = grants[f.key] ?? null;
		const stored = f.roles ?? null;
		const key = (list: string[] | null) => JSON.stringify((list ?? []).slice().sort());
		return key(current) !== key(stored);
	}

	async function save(f: AdminFeature) {
		saving = f.key;
		try {
			await adminFeatures.setGrant(f.key, grants[f.key] ?? null);
			toasts.success(i18n.t('features.saved', 'Feature updated'), f.label);
			await refresh();
		} catch (e) {
			toasts.danger(
				i18n.t('features.saveFailed', 'Save failed'),
				e instanceof Error ? e.message : String(e)
			);
		} finally {
			saving = null;
		}
	}

	const grantLabel = (grant: string[] | null) =>
		grant === null || grant === undefined
			? i18n.t('features.grantEveryone', 'Everyone')
			: grant.length === 0
				? i18n.t('features.grantAdmins', 'Admins only')
				: `${i18n.t('features.grantRoles', 'Roles')}: ${grant.join(', ')}`;
</script>

<div class="pane">
	<header class="mb-3">
		<h2>{i18n.t('admin.tabs.features', 'Features')}</h2>
		<p class="field__hint">
			{i18n.t(
				'features.intro',
				'Turn the main product surfaces on or off per role. Admins always keep access, and a denied caller is refused by the server even with a direct link. Every change is audited.'
			)}
		</p>
	</header>
	{#if error}<p class="callout callout--danger">{error}</p>{/if}
	{#if loading}
		<Skeleton rows={5} variant="table" />
	{:else}
		<table class="data-grid">
			<thead
				><tr>
					<th>{i18n.t('features.columnFeature', 'Feature')}</th>
					<th>{i18n.t('features.columnAccess', 'Access')}</th>
					<th>{i18n.t('admin.users.roles')}</th>
					<th></th>
				</tr></thead
			>
			<tbody>
				{#each features as f (f.key)}
					{@const current = grants[f.key]}
					<tr>
						<td>
							<div class="feature__label">{f.label}</div>
							<div class="field__hint">{f.description}</div>
							{#if !f.serverEnforced}
								<div class="field__hint">
									{i18n.t(
										'features.uiOnly',
										'Enforced in the UI only (this surface shares an API with a feature that stays on).'
									)}
								</div>
							{/if}
						</td>
						<td>
							<fieldset class="feature__modes">
								<label class="flex items-center gap-2">
									<input
										type="radio"
										name="mode-{f.key}"
										checked={modeOf(f) === 'everyone'}
										onchange={() => setMode(f, 'everyone')}
									/>
									{i18n.t('features.grantEveryone', 'Everyone')}
								</label>
								<label class="flex items-center gap-2">
									<input
										type="radio"
										name="mode-{f.key}"
										checked={modeOf(f) === 'admins'}
										onchange={() => setMode(f, 'admins')}
									/>
									{i18n.t('features.grantAdmins', 'Admins only')}
								</label>
								<label class="flex items-center gap-2">
									<input
										type="radio"
										name="mode-{f.key}"
										checked={modeOf(f) === 'roles'}
										onchange={() => setMode(f, 'roles')}
									/>
									{i18n.t('features.grantRoles', 'Selected roles')}
								</label>
							</fieldset>
						</td>
						<td>
							{#if modeOf(f) === 'roles'}
								<div class="feature__roles">
									{#each knownRoles as role (role)}
										<label class="flex items-center gap-2">
											<input
												type="checkbox"
												checked={current?.includes(role) ?? false}
												onchange={() => toggleRole(f, role)}
											/>
											{role}
										</label>
									{/each}
								</div>
							{:else}
								<span class="field__hint">{grantLabel(current ?? null)}</span>
							{/if}
						</td>
						<td class="data-grid__actions">
							<Button
								variant="outline"
								disabled={!dirty(f) || saving !== null}
								onclick={() => save(f)}
							>
								{saving === f.key ? '…' : i18n.t('modal.save')}
							</Button>
						</td>
					</tr>
				{/each}
			</tbody>
		</table>
		<section class="mt-4">
			<button type="button" class="audit__toggle" onclick={() => (auditOpen = !auditOpen)}>
				{auditOpen ? '▾' : '▸'}
				{i18n.t('features.auditTitle', 'Audit trail')}
				<span class="field__hint">{audit.length}</span>
			</button>
			{#if auditOpen}
				<table class="data-grid">
					<thead
						><tr>
							<th>{i18n.t('features.auditWhen', 'When')}</th>
							<th>{i18n.t('features.auditActor', 'Actor')}</th>
							<th>{i18n.t('features.auditChange', 'Change')}</th>
						</tr></thead
					>
					<tbody>
						{#each audit
							.slice()
							.reverse() as e (e.ts + e.feature + (e.after?.join(',') ?? 'reset'))}
							<tr>
								<td>{e.ts}</td>
								<td>{e.actor}</td>
								<td>
									{e.feature}
									<span class="field__hint">
										{grantLabel(e.before)} → {grantLabel(e.after)}
									</span>
								</td>
							</tr>
						{/each}
						{#if audit.length === 0}
							<tr
								><td colspan="3" class="data-grid__empty"
									>{i18n.t('features.auditEmpty', 'No changes recorded yet.')}</td
								></tr
							>
						{/if}
					</tbody>
				</table>
			{/if}
		</section>
	{/if}
</div>

<style>
	h2 {
		margin: 0;
	}
	/* .data-grid / .data-grid__actions / .data-grid__empty come from app.css */
	.field__hint {
		margin: var(--space-1) 0 0;
		color: hsl(var(--fg-subtle));
		font-size: var(--fs-xs);
	}
	.feature__label {
		font-weight: 600;
	}
	.feature__modes {
		display: flex;
		flex-direction: column;
		gap: var(--space-1);
		border: 0;
		padding: 0;
		margin: 0;
	}
	.feature__roles {
		display: flex;
		flex-direction: column;
		gap: var(--space-1);
	}
	.audit__toggle {
		display: inline-flex;
		align-items: center;
		gap: var(--space-2);
		background: transparent;
		border: 0;
		color: hsl(var(--fg));
		font: inherit;
		cursor: pointer;
		padding: var(--space-2) 0;
	}
</style>
