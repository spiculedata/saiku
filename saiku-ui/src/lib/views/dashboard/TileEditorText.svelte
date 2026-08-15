<script lang="ts">
	/*
	 * Text-tile editor — extracted from TileEditorModal.svelte (saiku#1229).
	 * Owns the markdown / HTML textarea for a text tile. State held as a
	 * $bindable() prop so the parent stays the persistence boundary.
	 */
	interface Props {
		text: string;
	}
	let { text = $bindable() }: Props = $props();
</script>

<label class="field">
	<span class="field__label">Markdown (sanitised on render)</span>
	<textarea class="field__input" bind:value={text} rows="10"></textarea>
</label>
