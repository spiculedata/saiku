<script lang="ts">
  /*
   * Text annotation tile. Stores raw text/HTML in tile.text and renders
   * it after sanitisation via DOMPurify. No query, no filters, no
   * subscriptions — purely declarative.
   *
   * Threat model: a malicious analyst with write access to the JCR
   * repository injects script tags or javascript: URLs. DOMPurify
   * strips scripts, event handlers, and dangerous URL schemes by
   * default; a sanity test in the project's vitest harness asserts
   * the common XSS payloads don't survive (task #14).
   */

  import DOMPurify from "dompurify";
  import type { DashboardTile } from "$lib/api/dashboards";

  interface Props {
    tile: DashboardTile;
  }

  let { tile }: Props = $props();

  // Stricter than DOMPurify's html-profile defaults — also forbid
  // style / embed / object / iframe / form tags so analysts can't
  // smuggle CSS-based exfil, inline plugins, or hidden form posts
  // through a TextTile. Script tags, event handlers, and
  // javascript:/data: URLs are stripped by DOMPurify's defaults.
  //
  // Note: USE_PROFILES has an additive effect that re-allows some of
  // these tags (notably embed when SVG appears in the input),
  // overriding FORBID_TAGS. Drop the profile and use DOMPurify's
  // default-strict mode — it covers the OWASP vectors we care about
  // and respects our forbid list cleanly.
  const SANITISE_CONFIG = {
    FORBID_TAGS: ["style", "embed", "object", "iframe", "form"],
  };

  let safeHtml = $derived(DOMPurify.sanitize(tile.text ?? "", SANITISE_CONFIG));
</script>

<div class="text-tile">
  <!-- eslint-disable-next-line svelte/no-at-html-tags — sanitised via DOMPurify -->
  {@html safeHtml}
</div>

<style>
  .text-tile {
    padding: 0.25rem 0.5rem;
    font-size: 0.875rem;
    line-height: 1.5;
    color: var(--fg);
  }
  /* Heading / paragraph / list resets so analyst-written text doesn't
     inherit weird vertical rhythm from the surrounding tile chrome. */
  .text-tile :global(h1),
  .text-tile :global(h2),
  .text-tile :global(h3) {
    margin: 0.25em 0;
    font-weight: var(--weight-semibold);
  }
  .text-tile :global(p) {
    margin: 0.25em 0;
  }
  .text-tile :global(ul),
  .text-tile :global(ol) {
    margin: 0.25em 0;
    padding-left: 1.25rem;
  }
  .text-tile :global(code) {
    background: var(--bg-subtle);
    padding: 0.0625em 0.25em;
    border-radius: 3px;
    font-size: 0.85em;
  }
  .text-tile :global(a) {
    color: var(--accent);
    text-decoration: underline;
  }
</style>
