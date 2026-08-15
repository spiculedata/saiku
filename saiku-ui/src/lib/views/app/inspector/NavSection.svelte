<script lang="ts">
	/* Inspector → Navigation. Rail vs top, collapsed default, and the pinned
	 * rail footer (settings gear + avatar). Writes via appDoc.updateNav. */
	import { appDoc } from '$lib/stores/appDoc.svelte';

	const nav = $derived(appDoc.current?.nav ?? { position: 'rail' as const });
	const footer = $derived(nav.footer ?? {});
	/** Three-way UI state over two persisted fields: an absent source with a
	 *  literal is a legacy "fixed" app; neither means the disc is off. */
	const source = $derived<'user' | 'fixed' | 'off'>(
		footer.avatarSource === 'user'
			? 'user'
			: footer.avatarSource === 'fixed'
				? 'fixed'
				: // Legacy: a literal with no declared source is a fixed disc.
					footer.avatar
					? 'fixed'
					: 'off'
	);
	const val = (e: Event) => (e.currentTarget as HTMLInputElement).value;
	const setFooter = (patch: Record<string, unknown>) =>
		appDoc.updateNav({ footer: { ...footer, ...patch } });
</script>

<div class="insp-section">
	<div class="insp-label">Placement</div>
	<div class="insp-row">
		<span>Menu</span>
		<div class="insp-seg">
			<button
				type="button"
				class:is-active={nav.position !== 'top'}
				onclick={() => appDoc.updateNav({ position: 'rail' })}>Rail</button
			>
			<button
				type="button"
				class:is-active={nav.position === 'top'}
				onclick={() => appDoc.updateNav({ position: 'top' })}>Top</button
			>
		</div>
	</div>
	{#if nav.position !== 'top'}
		<label class="insp-row insp-toggle"
			><span>Start collapsed (icons only)</span>
			<input
				type="checkbox"
				checked={nav.railCollapsed ?? false}
				onchange={(e) =>
					appDoc.updateNav({ railCollapsed: (e.currentTarget as HTMLInputElement).checked })}
			/>
		</label>
		<label class="insp-row insp-toggle"
			><span>Full height (header beside rail)</span>
			<input
				type="checkbox"
				checked={nav.railFullHeight ?? false}
				onchange={(e) =>
					appDoc.updateNav({ railFullHeight: (e.currentTarget as HTMLInputElement).checked })}
			/>
		</label>
		<p class="insp-hint">
			Off: the header spans the top and the rail starts below it. On: the rail is the outermost
			chrome, running the full height with the header to its right.
		</p>
	{/if}
</div>

{#if nav.position !== 'top'}
	<div class="insp-section">
		<div class="insp-label">Rail footer</div>
		<label class="insp-row insp-toggle"
			><span>Settings gear</span>
			<input
				type="checkbox"
				checked={footer.settings ?? false}
				onchange={(e) => setFooter({ settings: (e.currentTarget as HTMLInputElement).checked })}
			/>
		</label>
		<div class="insp-row">
			<span>User disc</span>
			<div class="insp-seg">
				<button
					type="button"
					class:is-active={source === 'user'}
					onclick={() => setFooter({ avatarSource: 'user' })}>Signed-in user</button
				>
				<button
					type="button"
					class:is-active={source === 'fixed'}
					onclick={() => setFooter({ avatarSource: 'fixed' })}>Fixed</button
				>
				<button
					type="button"
					class:is-active={source === 'off'}
					onclick={() => setFooter({ avatarSource: undefined, avatar: undefined })}>Off</button
				>
			</div>
		</div>
		{#if source === 'fixed'}
			<label class="insp-row"
				><span>Initials</span>
				<input
					class="insp-input"
					placeholder="e.g. RM"
					maxlength="3"
					value={footer.avatar ?? ''}
					oninput={(e) => setFooter({ avatar: val(e).trim() === '' ? undefined : val(e) })}
				/>
			</label>
		{/if}
		<p class="insp-hint">
			{#if source === 'user'}
				Initials come from whoever is signed in — the disc identifies the viewer, not the author.
			{:else if source === 'fixed'}
				The same initials for every viewer. Useful for a mock-up or a shared kiosk; not for a real
				user avatar.
			{:else}
				No user disc at the bottom of the rail.
			{/if}
		</p>
	</div>
{/if}
