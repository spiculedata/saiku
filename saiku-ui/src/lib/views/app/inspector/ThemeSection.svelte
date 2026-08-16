<script lang="ts">
	/* Inspector → Theme. Preset gallery + colour/type/form pickers + advanced
	 * CSS. Writes through appDoc (setTheme / applyPreset) for live preview. */
	import { appDoc } from '$lib/stores/appDoc.svelte';
	import {
		THEME_PRESETS,
		resolveTokens,
		type ResolvedTokens
	} from '$lib/dashboard/appThemePresets';
	import { FONT_ALLOWLIST, NUMERAL_CHOICES } from '$lib/dashboard/appTheme';
	import { themeFromBrief } from '$lib/dashboard/themeFromBrief';
	import { Sparkles } from '@lucide/svelte';

	const theme = $derived(appDoc.current?.theme ?? { mode: 'light' as const });
	const tok = $derived<ResolvedTokens>(resolveTokens(theme));

	const COLOURS: { k: keyof ResolvedTokens; label: string }[] = [
		{ k: 'accent', label: 'Accent' },
		{ k: 'accent2', label: 'Brand mark' },
		{ k: 'ground', label: 'Background' },
		{ k: 'surface', label: 'Cards' },
		{ k: 'fg', label: 'Text' },
		{ k: 'positive', label: 'Up / positive' },
		{ k: 'danger', label: 'Down / negative' }
	];
	const RADII: ResolvedTokens['radius'][] = ['none', 'sm', 'md', 'lg', 'xl'];
	const SHADOWS: ResolvedTokens['shadow'][] = ['none', 'sm', 'md', 'lg'];
	const DENSITIES: ResolvedTokens['density'][] = ['compact', 'cozy', 'comfortable'];
	const KPI_ACCENTS: ResolvedTokens['kpiAccent'][] = ['none', 'tone'];

	let advancedOpen = $state(false);
	let brief = $state('');
	const setToken = (k: keyof ResolvedTokens, v: string) => appDoc.setTheme({ [k]: v });

	/** Turn a plain-English brand brief into a theme (preset + overrides). */
	function applyBrief(): void {
		if (!brief.trim()) return;
		const { preset, ...overrides } = themeFromBrief(brief);
		if (preset) appDoc.applyPreset(preset);
		if (Object.keys(overrides).length > 0) appDoc.setTheme(overrides);
	}
</script>

<div class="insp-section">
	<div class="insp-label">Describe your brand</div>
	<textarea
		class="insp-textarea"
		rows="2"
		placeholder="e.g. warm editorial look, green accent, rounded cards"
		bind:value={brief}
	></textarea>
	<button type="button" class="brief-btn" onclick={applyBrief} disabled={!brief.trim()}>
		<Sparkles size={13} /> Generate theme
	</button>
</div>

<div class="insp-section">
	<div class="insp-label">Preset</div>
	<div class="presets">
		{#each THEME_PRESETS as p (p.key)}
			<button
				type="button"
				class="preset"
				class:is-active={theme.preset === p.key}
				title={p.note}
				onclick={() => appDoc.applyPreset(p.key)}
			>
				<span class="swatches" aria-hidden="true">
					<span style="background:{p.tokens.ground}"></span>
					<span style="background:{p.tokens.surface};border:1px solid {p.tokens.cardBorder}"></span>
					<span style="background:{p.tokens.accent}"></span>
					<span style="background:{p.tokens.accent2}"></span>
				</span>
				<span class="preset-name">{p.label}</span>
			</button>
		{/each}
	</div>
</div>

<div class="insp-section">
	<div class="insp-label">Colours</div>
	<div class="colours">
		{#each COLOURS as c (c.k)}
			<label class="colour">
				<input
					type="color"
					value={String(tok[c.k])}
					oninput={(e) => setToken(c.k, (e.currentTarget as HTMLInputElement).value)}
				/>
				<span>{c.label}</span>
			</label>
		{/each}
	</div>
</div>

<div class="insp-section">
	<div class="insp-label">Type</div>
	<label class="insp-row"
		><span>Headings</span>
		<select
			class="insp-select"
			value={tok.fontDisplay}
			onchange={(e) => setToken('fontDisplay', (e.currentTarget as HTMLSelectElement).value)}
		>
			{#each FONT_ALLOWLIST as f (f.key)}<option value={f.key}>{f.label}</option>{/each}
		</select>
	</label>
	<label class="insp-row"
		><span>Body</span>
		<select
			class="insp-select"
			value={tok.fontBody}
			onchange={(e) => setToken('fontBody', (e.currentTarget as HTMLSelectElement).value)}
		>
			{#each FONT_ALLOWLIST as f (f.key)}<option value={f.key}>{f.label}</option>{/each}
		</select>
	</label>
	<label class="insp-row"
		><span>Numbers</span>
		<select
			class="insp-select"
			value={tok.numerals}
			onchange={(e) => setToken('numerals', (e.currentTarget as HTMLSelectElement).value)}
		>
			{#each NUMERAL_CHOICES as n (n.key)}<option value={n.key}>{n.label}</option>{/each}
		</select>
	</label>
	<p class="insp-hint">KPI headlines and numeric table cells. Monospace keeps figures aligned.</p>
</div>

<div class="insp-section">
	<div class="insp-label">Form</div>
	<div class="insp-row">
		<span>Corners</span>
		<div class="insp-seg">
			{#each RADII as r (r)}<button
					type="button"
					class:is-active={tok.radius === r}
					onclick={() => setToken('radius', r)}>{r}</button
				>{/each}
		</div>
	</div>
	<div class="insp-row">
		<span>Shadow</span>
		<div class="insp-seg">
			{#each SHADOWS as sh (sh)}<button
					type="button"
					class:is-active={tok.shadow === sh}
					onclick={() => setToken('shadow', sh)}>{sh}</button
				>{/each}
		</div>
	</div>
	<div class="insp-row">
		<span>Density</span>
		<div class="insp-seg">
			{#each DENSITIES as d (d)}<button
					type="button"
					class:is-active={tok.density === d}
					onclick={() => setToken('density', d)}>{d}</button
				>{/each}
		</div>
	</div>
	<div class="insp-row">
		<span>KPI edge bar</span>
		<div class="insp-seg">
			{#each KPI_ACCENTS as k (k)}<button
					type="button"
					class:is-active={tok.kpiAccent === k}
					onclick={() => setToken('kpiAccent', k)}>{k}</button
				>{/each}
		</div>
	</div>
	<p class="insp-hint">"Tone" colours each KPI's left edge by whether it's up or down.</p>
</div>

<div class="insp-section">
	<button
		type="button"
		class="disclosure"
		aria-expanded={advancedOpen}
		onclick={() => (advancedOpen = !advancedOpen)}
	>
		{advancedOpen ? '▾' : '▸'} Advanced — custom CSS
	</button>
	{#if advancedOpen}
		<p class="insp-hint">
			Escape hatch for the long tail. Sanitised + scoped to this app — you shouldn't need it for
			standard branding.
		</p>
		<textarea
			class="insp-textarea"
			rows="8"
			spellcheck="false"
			placeholder="/* scoped custom CSS */"
			value={theme.customCss ?? ''}
			oninput={(e) =>
				appDoc.setTheme({ customCss: (e.currentTarget as HTMLTextAreaElement).value })}
		></textarea>
	{/if}
</div>

<style>
	.presets {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 0.5rem;
	}
	.preset {
		display: flex;
		flex-direction: column;
		gap: 0.4rem;
		padding: 0.5rem;
		border: 1px solid hsl(var(--border));
		border-radius: 8px;
		background: hsl(var(--bg-subtle));
		cursor: pointer;
		text-align: left;
	}
	.preset.is-active {
		border-color: hsl(var(--primary));
		box-shadow: 0 0 0 1px hsl(var(--primary));
	}
	.swatches {
		display: flex;
		height: 22px;
		border-radius: 5px;
		overflow: hidden;
	}
	.swatches span {
		flex: 1;
	}
	.preset-name {
		font-size: 0.8rem;
		font-weight: 600;
	}
	.colours {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 0.5rem 0.75rem;
	}
	.colour {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-size: 0.8rem;
	}
	.colour input[type='color'] {
		width: 28px;
		height: 24px;
		padding: 0;
		border: 1px solid hsl(var(--border));
		border-radius: 5px;
		background: none;
		cursor: pointer;
		flex-shrink: 0;
	}
	.disclosure {
		border: 0;
		background: transparent;
		color: hsl(var(--fg-muted));
		font-size: 0.78rem;
		font-weight: 600;
		text-align: left;
		padding: 0;
		cursor: pointer;
	}
	.brief-btn {
		align-self: flex-start;
		display: inline-flex;
		align-items: center;
		gap: 6px;
		border: 0;
		border-radius: 6px;
		padding: 0.35rem 0.7rem;
		background: hsl(var(--primary));
		color: var(--accent-fg, #fff);
		font-size: 0.78rem;
		font-weight: 600;
		cursor: pointer;
	}
	.brief-btn:disabled {
		opacity: 0.5;
		cursor: default;
	}
</style>
