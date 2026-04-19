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
  .locale { cursor: pointer; padding-right: 6px; }
  .locale select {
    background: transparent;
    color: inherit;
    border: none;
    outline: none;
    font: inherit;
    cursor: pointer;
    appearance: none;
    padding: 0 18px 0 0;
    background-image: linear-gradient(45deg, transparent 50%, currentColor 50%),
                      linear-gradient(135deg, currentColor 50%, transparent 50%);
    background-position: calc(100% - 10px) 55%, calc(100% - 5px) 55%;
    background-size: 5px 5px, 5px 5px;
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
