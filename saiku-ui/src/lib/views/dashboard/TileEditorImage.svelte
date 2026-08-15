<script lang="ts">
	/*
	 * Image-tile editor fields — extracted from TileEditorModal.svelte (saiku#1229).
	 *
	 * Owns the URL / upload / fit / caption / alt controls for an image tile.
	 * State is held as $bindable() props so the parent stays the persistence
	 * boundary (handleSave still reads imageFile / imageUrl / etc to build the
	 * ImageConfig payload). The parent decides when this component renders —
	 * gated on `{#if tile.type === "image"}` upstream.
	 *
	 * No business logic; pure UI surface. Lifted out of the 1726-line parent
	 * so the image-tile editor can be reviewed + tested in isolation, and so
	 * the same pattern can be repeated for chart/table/filter/kpi/text
	 * delegates per #1229's split plan.
	 */
	import type { ImageSource, ImageFit } from '$lib/api/dashboards';

	interface Props {
		imageMode: ImageSource;
		imageUrl: string;
		imageExistingSrc: string;
		imageFit: ImageFit;
		imageCaption: string;
		imageAlt: string;
		imageFile: File | null;
		/** Cleared when the user picks a new file — propagates back to the parent's
		 *  body-error display so a previously-rejected upload's error message
		 *  vanishes once the user starts over. */
		onFileChosen?: () => void;
	}

	let {
		imageMode = $bindable(),
		imageUrl = $bindable(),
		imageExistingSrc,
		imageFit = $bindable(),
		imageCaption = $bindable(),
		imageAlt = $bindable(),
		imageFile = $bindable(),
		onFileChosen
	}: Props = $props();
</script>

<fieldset class="mode">
	<legend>Image source</legend>
	<label class="radio">
		<input type="radio" bind:group={imageMode} value="url" />
		<span>URL</span>
	</label>
	<label class="radio">
		<input type="radio" bind:group={imageMode} value="upload" />
		<span>Upload file</span>
	</label>
</fieldset>

{#if imageMode === 'url'}
	<label class="field">
		<span class="field__label">Image URL (http/https)</span>
		<input
			class="field__input"
			type="url"
			bind:value={imageUrl}
			placeholder="https://example.com/logo.png"
		/>
	</label>
{:else}
	<label class="field">
		<span class="field__label">Upload image{imageExistingSrc ? ' (replaces current)' : ''}</span>
		<input
			class="field__input"
			type="file"
			accept="image/png,image/jpeg,image/gif,image/webp"
			onchange={(e) => {
				imageFile = (e.currentTarget as HTMLInputElement).files?.[0] ?? null;
				onFileChosen?.();
			}}
		/>
		<span class="hint">
			PNG, JPEG, GIF or WebP, up to 2&nbsp;MB. Stored privately in your repository.{imageExistingSrc &&
			!imageFile
				? ' An image is already set.'
				: ''}
		</span>
	</label>
{/if}

<label class="field">
	<span class="field__label">Fit</span>
	<select class="field__input" bind:value={imageFit}>
		<option value="contain">Contain (whole image, letterboxed)</option>
		<option value="cover">Cover (fill tile, crop edges)</option>
		<option value="fill">Fill (stretch to tile)</option>
		<option value="scale-down">Scale down (never upscale)</option>
	</select>
</label>

<label class="field">
	<span class="field__label">Caption (optional)</span>
	<input
		class="field__input"
		type="text"
		bind:value={imageCaption}
		placeholder="e.g. Q4 campaign"
	/>
</label>

<label class="field">
	<span class="field__label">Alt text (accessibility)</span>
	<input class="field__input" type="text" bind:value={imageAlt} placeholder="Describe the image" />
</label>

<style>
	/* saiku#1258: fields use the global app.css design-system pattern
     (.field / .field__label / .field__input) — the old comment claimed the
     parent modal's scoped styles reached here, which Svelte scoping never
     actually allowed. */
</style>
