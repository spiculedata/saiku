<script lang="ts">
  /*
   * Image tile (issue #918). Renders a static image — a company logo, a
   * process diagram, a legend — from either an external URL or an asset
   * uploaded into the repository. No query, no filters; purely declarative,
   * like TextTile.
   *
   * Security: the src is only honoured if it resolves to an http(s) URL
   * (absolute) or a same-origin relative path (e.g. the upload endpoint's
   * download path). javascript:/data:/vbscript: and any other scheme are
   * rejected → placeholder. <img> never executes script from src, and SVG
   * loaded via <img> is non-scripting, so an http(s)-only allowlist is a
   * sufficient client-side guard; the upload endpoint does its own
   * content-type + path hardening server-side.
   */
  import { ImageOff, Image as ImageIcon, Settings2 } from "lucide-svelte";
  import type { DashboardTile } from "$lib/api/dashboards";
  import { safeImageSrc, coerceImageFit } from "$lib/dashboard/imageSrc";

  interface Props {
    tile: DashboardTile;
  }
  let { tile }: Props = $props();

  let imgSrc = $derived(safeImageSrc(tile.image?.src));
  let fit = $derived(coerceImageFit(tile.image?.fit));
  let caption = $derived((tile.image?.caption ?? "").trim());
  let alt = $derived(tile.image?.alt?.trim() || caption || tile.title || "Dashboard image");

  // Reset the broken-image flag whenever the source changes.
  let broken = $state(false);
  $effect(() => {
    void imgSrc;
    broken = false;
  });
</script>

{#if !imgSrc}
  <div class="image-placeholder">
    <ImageIcon size={22} aria-hidden="true" />
    <span>
      No image set — open
      <Settings2 size={13} class="inline-gear" aria-label="tile settings" />
      to add a URL or upload one.
    </span>
  </div>
{:else if broken}
  <div class="image-placeholder">
    <ImageOff size={22} aria-hidden="true" />
    <span>Couldn't load image.</span>
  </div>
{:else}
  <figure class="image-tile">
    <img
      src={imgSrc}
      {alt}
      style="object-fit: {fit}"
      onerror={() => (broken = true)}
    />
    {#if caption}<figcaption>{caption}</figcaption>{/if}
  </figure>
{/if}

<style>
  .image-tile {
    margin: 0;
    height: 100%;
    width: 100%;
    display: flex;
    flex-direction: column;
    min-height: 0;
  }
  .image-tile img {
    flex: 1;
    min-height: 0;
    width: 100%;
    /* object-fit is set inline (contain | cover | fill | scale-down). */
    object-position: center;
  }
  .image-tile figcaption {
    flex-shrink: 0;
    padding: 0.25rem 0.5rem;
    font-size: 0.75rem;
    color: var(--fg-muted);
    text-align: center;
    overflow-wrap: anywhere;
  }
  .image-placeholder {
    height: 100%;
    width: 100%;
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 0.5rem;
    padding: 0.75rem;
    text-align: center;
    color: var(--fg-muted);
    font-size: 0.8125rem;
  }
  /* Render the real edit-button icon (lucide Settings2) inline so the
     placeholder hint matches the tile's title-bar control exactly. */
  .image-placeholder :global(.inline-gear) {
    display: inline-block;
    vertical-align: -2px;
    color: var(--fg-subtle);
  }
</style>
