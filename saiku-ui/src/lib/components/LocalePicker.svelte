<script lang="ts">
  import { i18n, LOCALES, type Locale } from "$lib/stores/i18n.svelte";
  import { Languages } from "lucide-svelte";
</script>

<label class="locale btn" title="Language">
  <Languages size={14} />
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
  .locale { cursor: pointer; line-height: 1; }
  .locale select {
    background: transparent;
    color: inherit;
    border: none;
    outline: none;
    font: inherit;
    line-height: inherit;
    cursor: pointer;
    appearance: none;
    margin: 0;
    padding: 0 14px 0 0;
    height: auto;
    background-image: linear-gradient(45deg, transparent 50%, currentColor 50%),
                      linear-gradient(135deg, currentColor 50%, transparent 50%);
    background-position: calc(100% - 8px) 55%, calc(100% - 4px) 55%;
    background-size: 4px 4px, 4px 4px;
    background-repeat: no-repeat;
  }
  .locale select:focus { outline: none; }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
  }
</style>
