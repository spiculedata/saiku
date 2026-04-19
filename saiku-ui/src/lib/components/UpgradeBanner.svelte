<script lang="ts">
  import { browser } from "$app/environment";
  import { platform } from "$lib/stores/platform.svelte";

  const STORAGE_KEY = "saiku.upgrade.dismissed";

  let dismissed = $state<boolean>(
    browser && localStorage.getItem(STORAGE_KEY) === "1",
  );

  function dismiss() {
    dismissed = true;
    if (browser) localStorage.setItem(STORAGE_KEY, "1");
  }
</script>

{#if !dismissed && platform.newVersionAvailable}
  <div class="upgrade" role="status">
    <span>
      A newer version of Saiku is available{platform.version ? ` (current: ${platform.version})` : ""}.
    </span>
    <a class="upgrade__cta" href="https://github.com/OSBI/saiku/releases" target="_blank" rel="noopener">Release notes</a>
    <button type="button" class="upgrade__close" onclick={dismiss} aria-label="Dismiss">×</button>
  </div>
{/if}

<style>
  .upgrade {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-5);
    background: var(--warning);
    color: #111;
    font-size: var(--fs-sm);
  }
  .upgrade__cta { color: inherit; text-decoration: underline; }
  .upgrade__close {
    margin-left: auto;
    background: transparent;
    border: 0;
    color: inherit;
    cursor: pointer;
    font-size: 18px;
    line-height: 1;
  }
</style>
