<script lang="ts">
  import { browser } from "$app/environment";
  import { platform } from "$lib/stores/platform.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { FeedbackBanner } from "$lib/design-system";
  import { X } from "@lucide/svelte";

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
  <FeedbackBanner tone="warning" size="sm" testid="upgrade-banner">
    <div class="flex items-center gap-3">
      <span class="flex-1">
        {i18n.t("upgrade.available")}{platform.version
          ? ` (${i18n.t("upgrade.current")}: ${platform.version})`
          : ""}.
        <a
          href="https://github.com/OSBI/saiku/releases"
          target="_blank"
          rel="noopener"
          class="underline"
        >
          {i18n.t("upgrade.releaseNotes")}
        </a>
      </span>
      <button
        type="button"
        class="ml-auto inline-flex items-center justify-center rounded p-1 hover:bg-warning/20"
        onclick={dismiss}
        aria-label={i18n.t("upgrade.dismiss")}
      >
        <X size={14} />
      </button>
    </div>
  </FeedbackBanner>
{/if}
