<script lang="ts">
	/*
	 * Roles admin tab (saiku#779). Shows which Spring role grants which Mondrian role on which
	 * datasource and who holds it, previews what a user (or a set of roles) would actually get
	 * ("test as"), and edits the Spring -> Mondrian grants of lookup-mode datasources. The
	 * Mondrian roles themselves (cube / hierarchy / member grants) are <Role> elements in the
	 * schema; this tab maps callers onto them. Previews run the server's enforcement code, so
	 * what's shown here is what the user gets.
	 */
	import { onMount } from 'svelte';
	import { Button, Input } from '$lib/components/ui';
	import { Badge, FormField, Skeleton } from '$lib/design-system';
	import Modal from '$lib/components/Modal.svelte';
	import { toasts } from '$lib/stores/toasts.svelte';
	import { i18n } from '$lib/stores/i18n.svelte';
	import {
		adminRoles,
		type DatasourceRoleSecurity,
		type RoleOverview,
		type RolePreview
	} from '$lib/api/admin';
	import {
		accessHint,
		accessLabel,
		accessTone,
		modeLabel,
		parseRoleList,
		toggle
	} from './roleAccess';

	let overview = $state<RoleOverview | null>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);

	// "Test as" preview.
	let previewBy = $state<'user' | 'roles'>('user');
	let previewUser = $state('');
	let previewRolesText = $state('');
	let preview = $state<RolePreview | null>(null);
	let previewing = $state(false);

	// Grant editor for a lookup-mode datasource.
	let editing = $state<{
		datasource: DatasourceRoleSecurity;
		role: string;
		isNew: boolean;
		grants: string[];
	} | null>(null);
	let saving = $state(false);

	const knownUsers = $derived(
		overview ? [...new Set(overview.roles.flatMap((r) => r.users))].sort() : []
	);

	async function refresh() {
		loading = true;
		error = null;
		try {
			overview = await adminRoles.overview();
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	onMount(refresh);

	async function runPreview() {
		previewing = true;
		try {
			preview =
				previewBy === 'user'
					? await adminRoles.previewUser(previewUser)
					: await adminRoles.previewRoles(parseRoleList(previewRolesText));
		} catch (e) {
			preview = null;
			toasts.danger('Preview failed', e instanceof Error ? e.message : String(e));
		} finally {
			previewing = false;
		}
	}

	function editGrant(ds: DatasourceRoleSecurity, role: string) {
		editing = { datasource: ds, role, isNew: false, grants: [...(ds.mapping[role] ?? [])] };
	}

	function addGrant(ds: DatasourceRoleSecurity) {
		editing = { datasource: ds, role: '', isNew: true, grants: [] };
	}

	async function saveGrant(grants: string[]) {
		if (!editing) return;
		const role = editing.role.trim();
		if (!role) {
			toasts.danger('Save failed', 'Enter a Spring role name.');
			return;
		}
		saving = true;
		try {
			await adminRoles.setGrants(role, editing.datasource.name, grants);
			toasts.success(
				grants.length ? 'Grants saved' : 'Grants revoked',
				`${role} on ${editing.datasource.name}`
			);
			editing = null;
			await refresh();
			if (preview) await runPreview();
		} catch (e) {
			toasts.danger('Save failed', e instanceof Error ? e.message : String(e));
		} finally {
			saving = false;
		}
	}
</script>

<div class="pane">
	<header class="mb-3 flex items-center justify-between">
		<h2>{i18n.t('admin.tabs.roles')}</h2>
		<Button variant="outline" onclick={refresh}>Refresh</Button>
	</header>
	<p class="lede">
		Spring roles (from sign-in) map to Mondrian roles declared as <code>&lt;Role&gt;</code> in each datasource's
		schema. Mondrian roles restrict cubes, hierarchies and members. A datasource with security on denies
		a non-admin whose roles map to nothing.
	</p>

	{#if error}<p class="callout callout--danger">{error}</p>{/if}

	<section class="block">
		<h3>Test access</h3>
		<div class="preview-form">
			<label class="flex items-center gap-2">
				<input type="radio" bind:group={previewBy} value="user" /> As user
			</label>
			<label class="flex items-center gap-2">
				<input type="radio" bind:group={previewBy} value="roles" /> As roles
			</label>
			{#if previewBy === 'user'}
				<select class="field__input" bind:value={previewUser} aria-label="User">
					<option value="" disabled>Select a user…</option>
					{#each knownUsers as u}<option value={u}>{u}</option>{/each}
				</select>
			{:else}
				<Input bind:value={previewRolesText} placeholder="ROLE_SALES, ROLE_EU" aria-label="Roles" />
			{/if}
			<Button
				onclick={runPreview}
				disabled={previewing ||
					(previewBy === 'user' ? !previewUser : parseRoleList(previewRolesText).length === 0)}
				>Preview</Button
			>
		</div>
		{#if preview}
			<p class="meta">
				{preview.username ? `${preview.username}: ` : ''}{preview.roles.join(', ') || 'no roles'}
				{#if preview.admin}<Badge tone="info">admin</Badge>{/if}
			</p>
			<table class="data-grid">
				<thead><tr><th>Datasource</th><th>Security</th><th>Access</th><th>Runs as</th></tr></thead>
				<tbody>
					{#each preview.datasources as d}
						<tr>
							<td>{d.datasource}</td>
							<td>{modeLabel(d.mode)}</td>
							<td title={accessHint(d.access)}
								><Badge tone={accessTone(d.access)}>{accessLabel(d.access)}</Badge></td
							>
							<td>{d.mondrianRoles.join(', ')}</td>
						</tr>
					{/each}
					{#if preview.datasources.length === 0}
						<tr><td colspan="4" class="data-grid__empty">{i18n.t('admin.empty')}</td></tr>
					{/if}
				</tbody>
			</table>
		{/if}
	</section>

	{#if loading}
		<Skeleton rows={4} variant="table" />
	{:else if overview}
		<section class="block">
			<h3>Roles</h3>
			<table class="data-grid">
				<thead><tr><th>Role</th><th>Users</th><th>Grants</th></tr></thead>
				<tbody>
					{#each overview.roles as r}
						<tr>
							<td
								>{r.name}
								{#if r.admin}<Badge tone="info">admin</Badge>{/if}</td
							>
							<td>{r.users.join(', ')}</td>
							<td>
								{#each r.grants as g}
									<div><strong>{g.datasource}</strong>: {g.mondrianRoles.join(', ')}</div>
								{/each}
							</td>
						</tr>
					{/each}
					{#if overview.roles.length === 0}
						<tr><td colspan="3" class="data-grid__empty">{i18n.t('admin.empty')}</td></tr>
					{/if}
				</tbody>
			</table>
			<p class="hint">
				Users come from the Saiku user store. Accounts that sign in through
				<code>users.properties</code>, LDAP or SSO only show up here if they're also in that store.
				Use <em>As roles</em> to test them.
			</p>
		</section>

		<section class="block">
			<h3>Datasources</h3>
			<table class="data-grid">
				<thead
					><tr><th>Datasource</th><th>Security</th><th>Schema roles</th><th>Grants</th><th></th></tr
					></thead
				>
				<tbody>
					{#each overview.datasources as ds}
						<tr>
							<td>{ds.name}</td>
							<td>{modeLabel(ds.mode)}</td>
							<td>{ds.mondrianRoles === null ? '—' : ds.mondrianRoles.join(', ') || 'none'}</td>
							<td>
								{#if ds.mode === 'LOOKUP'}
									{#each Object.entries(ds.mapping) as [spring, mondrian]}
										<div class="grant">
											<span><strong>{spring}</strong> → {mondrian.join(', ')}</span>
											<Button variant="outline" size="sm" onclick={() => editGrant(ds, spring)}
												>{i18n.t('admin.edit')}</Button
											>
										</div>
									{/each}
								{:else if ds.mode === 'ONE2ONE'}
									<span class="hint">A Spring role grants the schema role with the same name.</span>
								{/if}
							</td>
							<td class="data-grid__actions">
								{#if ds.mode === 'LOOKUP'}
									<Button variant="outline" onclick={() => addGrant(ds)}>Add grant</Button>
								{/if}
							</td>
						</tr>
					{/each}
					{#if overview.datasources.length === 0}
						<tr><td colspan="5" class="data-grid__empty">{i18n.t('admin.empty')}</td></tr>
					{/if}
				</tbody>
			</table>
			<p class="hint">
				You can edit grants here for datasources in lookup mode (<code>security.enabled=true</code>,
				<code>security.type=lookup</code>). Set the mode in the datasource's advanced properties.
			</p>
		</section>
	{/if}
</div>

<Modal
	title={editing?.isNew ? 'Add grant' : `Grants for ${editing?.role ?? ''}`}
	open={editing !== null}
	size="md"
	onClose={() => (editing = null)}
>
	{#if editing}
		<p class="meta">Datasource: <strong>{editing.datasource.name}</strong></p>
		{#if editing.isNew}
			<FormField label="Spring role">
				<Input bind:value={editing.role} placeholder="ROLE_SALES" />
			</FormField>
		{/if}
		<fieldset class="field">
			<legend class="field__label">Mondrian roles</legend>
			{#if editing.datasource.mondrianRoles === null}
				<p class="hint">Couldn't read this datasource's schema roles. Check the connection.</p>
			{:else if editing.datasource.mondrianRoles.length === 0}
				<p class="hint">The schema declares no <code>&lt;Role&gt;</code> elements.</p>
			{:else}
				{#each editing.datasource.mondrianRoles as m}
					<label class="flex items-center gap-2 px-0 py-1">
						<input
							type="checkbox"
							checked={editing.grants.includes(m)}
							onchange={() => editing && (editing.grants = toggle(editing.grants, m))}
						/>
						{m}
					</label>
				{/each}
			{/if}
		</fieldset>
	{/if}
	{#snippet footer()}
		{#if editing && !editing.isNew}
			<Button variant="destructive" disabled={saving} onclick={() => saveGrant([])}
				>Revoke all</Button
			>
		{/if}
		<Button variant="outline" onclick={() => (editing = null)}>{i18n.t('modal.cancel')}</Button>
		<Button
			disabled={saving || !editing || editing.grants.length === 0}
			onclick={() => editing && saveGrant(editing.grants)}>{i18n.t('modal.save')}</Button
		>
	{/snippet}
</Modal>

<style>
	h2,
	h3 {
		margin: 0;
	}
	h3 {
		margin-bottom: var(--space-2);
		font-size: var(--fs-md);
	}
	.block {
		margin-bottom: var(--space-6);
	}
	.lede,
	.meta {
		margin: 0 0 var(--space-3);
		color: hsl(var(--fg-muted));
	}
	.hint {
		margin: var(--space-1) 0 0;
		color: hsl(var(--fg-subtle));
		font-size: var(--fs-xs);
	}
	.preview-form {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: var(--space-3);
		margin-bottom: var(--space-3);
	}
	.preview-form select {
		min-width: 12rem;
	}
	.grant {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: var(--space-2);
		padding: var(--space-1) 0;
	}
</style>
