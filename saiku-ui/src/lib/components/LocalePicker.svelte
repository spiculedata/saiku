<script lang="ts">
  import { i18n, LOCALES, type Locale } from "$lib/stores/i18n.svelte";
</script>

<label class="locale">
  <span class="sr-only">Language</span>
  <select
    value={i18n.locale}
    onchange={(e) => i18n.set((e.currentTarget as HTMLSelectElement).value as Locale)}
  >
    {#each LOCALES as l}
      <option value={l.id}>{l.label}</option>
    {/each}
  </select>
</label>

<style>
  .locale select {
    padding: var(--space-1) var(--space-2);
    background: var(--bg);
    color: var(--fg);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    font: inherit;
  }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
  }
</style>
