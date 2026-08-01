<script lang="ts">
  import { Settings, Moon, Sun, Monitor, Contrast } from "lucide-svelte";
  import { Button } from "$lib/components/ui";
  import { theme } from "$lib/stores/theme.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import LocalePicker from "$lib/components/LocalePicker.svelte";

  interface Props {
    /** Panel open direction. "up" (default) suits a bottom-anchored host like
     *  the workspace sidebar footer; "down" suits a top toolbar (saiku#1050,
     *  the dashboard toolbar) so the panel doesn't open off the top of screen. */
    placement?: "up" | "down";
  }
  let { placement = "up" }: Props = $props();

  let open = $state(false);

  function onDocumentClick(e: MouseEvent) {
    if (!open) return;
    const target = e.target as Node | null;
    const host = document.querySelector(".prefs-menu");
    if (host && target && host.contains(target)) return;
    open = false;
  }
</script>

<svelte:window onclick={onDocumentClick} />

<div class="prefs-menu">
  <Button variant="outline" aria-haspopup="menu" aria-expanded={open} aria-label={i18n.t("topbar.preferences")} title={i18n.t("topbar.preferences")} onclick={(e) => { e.stopPropagation(); open = !open; }}>
    <Settings size={14} />
  </Button>
  {#if open}
    <div class="prefs-menu__panel {placement === "down" ? 'prefs-menu__panel--down' : ''}" role="menu">
      <div class="flex items-center justify-between gap-3">
        <span class="text-sm text-fg-muted">{i18n.t("topbar.theme")}</span>
        <Button variant="outline" size="sm" onclick={() => theme.toggle()} title={i18n.t("topbar.theme.cycle")}>
          {#if theme.theme === "dark"}
            <Moon size={14} /><span>{i18n.t("topbar.theme.dark")}</span>
          {:else if theme.theme === "light"}
            <Sun size={14} /><span>{i18n.t("topbar.theme.light")}</span>
          {:else}
            <Monitor size={14} /><span>{i18n.t("topbar.theme.system")}</span>
          {/if}
        </Button>
      </div>
      <div class="flex items-center justify-between gap-3">
        <span class="text-sm text-fg-muted">{i18n.t("topbar.contrast")}</span>
        <Button variant="outline" size="sm" class={theme.colorBlindSafe ? 'btn--active' : ''} role="switch" aria-checked={theme.colorBlindSafe} onclick={() => theme.toggleColorBlindSafe()} title={i18n.t("topbar.contrast.hint")}>
          <Contrast size={14} />
          <span>{theme.colorBlindSafe ? i18n.t("topbar.contrast.on") : i18n.t("topbar.contrast.off")}</span>
        </Button>
      </div>
      <div class="flex items-center justify-between gap-3">
        <span class="text-sm text-fg-muted">{i18n.t("topbar.language")}</span>
        <LocalePicker />
      </div>
    </div>
  {/if}
</div>

<style>
.prefs-menu { position: relative; }
  .prefs-menu__panel {
    position: absolute;
    bottom: calc(100% + 6px);
    left: 0;
    z-index: 30;
    background: hsl(var(--bg));
    border: 1px solid hsl(var(--border));
    border-radius: var(--radius);
    box-shadow: var(--shadow);
    padding: var(--space-3);
    min-width: 240px;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }
  /* Top-toolbar placement (saiku#1050): open downward, aligned to the
     button's right edge so the panel stays on-screen in the dashboard toolbar. */
  .prefs-menu__panel--down {
    bottom: auto;
    top: calc(100% + 6px);
    left: auto;
    right: 0;
  }
  /* #1091: active state for the colour-blind-safe toggle. */
</style>
