<script lang="ts">
  /*
   * Embed variant of the `plugin` custom tile (App Builder Phase 2, Task 7,
   * saiku#1441). Token-scoped, read-only, self-contained: renders inside the
   * <saiku-embed/> bundle, so it uses ONLY relative imports (no `$lib` alias).
   *
   * SECURITY posture is IDENTICAL to the in-app PluginTile: the exact same
   * iframe sandbox="allow-scripts", the same strict CSP + per-mount nonce (from
   * pluginBridge, which is pure and imports nothing), and the same
   * event.source === iframe.contentWindow + nonce message authentication.
   *
   * Data path: the `rows` prop is the token-scoped, RLS/PII-filtered payload
   * <EmbedGrid> already fetched through the guarded per-tile embed query. This
   * component adds NO second, unfiltered fetch — it only forwards those rows
   * into the frame. The embed surface is read-only, so `filter` requests from a
   * plugin are ignored (there is no cross-filter bus here).
   */

  import { onDestroy } from "svelte";
  import { buildSrcdoc, handlePluginMessage, PLUGIN_MIN_H } from "../../../../dashboard/custom/pluginBridge";

  /** Minimal cell shape (matches the embed EmbedCell) — kept local so this
   *  component doesn't reach into the embed module graph. */
  interface Cell {
    value: number | null;
    formatted: string;
  }
  type Row = Record<string, Cell>;

  interface Props {
    tile: { title?: string; custom?: { renderer: string; options?: Record<string, unknown> } };
    /** Token-scoped rows from <EmbedGrid>. undefined/null = still loading. */
    rows?: Row[] | null;
  }

  let { tile, rows }: Props = $props();

  let pluginHtml = $derived(
    typeof tile.custom?.options?.html === "string" ? (tile.custom.options.html as string) : "",
  );
  let hasPlugin = $derived(pluginHtml.trim().length > 0);
  let pluginOptions = $derived.by(() => {
    const opts = { ...(tile.custom?.options ?? {}) } as Record<string, unknown>;
    delete opts.html;
    return opts;
  });

  // Fresh cryptographic nonce per mount (not the tile id, not Math.random).
  const nonce = crypto.randomUUID();
  let srcdoc = $derived(hasPlugin ? buildSrcdoc(pluginHtml, nonce) : "");

  let iframe = $state<HTMLIFrameElement | null>(null);
  let frameReady = $state(false);
  let frameHeight = $state<number | null>(null);
  let pluginError = $state<string | null>(null);

  function prefersDark(): boolean {
    return typeof matchMedia === "function" && matchMedia("(prefers-color-scheme: dark)").matches;
  }

  function postToFrame(type: "init" | "data" | "theme", payload: unknown): void {
    const win = iframe?.contentWindow;
    if (!win) return;
    win.postMessage({ type, nonce, payload }, "*");
  }

  // On `ready` (and on rows change) push init + the token-scoped rows + theme.
  $effect(() => {
    if (!frameReady) return;
    const data = rows ?? [];
    postToFrame("init", { options: pluginOptions });
    postToFrame("data", data);
    postToFrame("theme", { effective: prefersDark() ? "dark" : "light" });
  });

  function onMessage(event: MessageEvent): void {
    // Only THIS frame; nonce authenticates. event.origin is "null" — never trust it.
    if (!iframe || event.source !== iframe.contentWindow) return;
    const msg = handlePluginMessage(event.data, nonce);
    if (!msg) return;
    switch (msg.kind) {
      case "ready":
        frameReady = true;
        break;
      case "resize":
        frameHeight = msg.height;
        break;
      case "error":
        pluginError = msg.message; // TEXT only, never {@html}
        break;
      case "filter":
        // Read-only embed surface: no cross-filter bus, so drop it.
        break;
    }
  }

  $effect(() => {
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  });

  $effect(() => {
    void srcdoc;
    frameReady = false;
  });

  onDestroy(() => {
    window.removeEventListener("message", onMessage);
  });
</script>

{#if !hasPlugin}
  <div class="state muted">No plugin configured.</div>
{:else if rows === undefined || rows === null}
  <div class="state muted">Loading…</div>
{:else}
  <div class="plugin-tile">
    <iframe
      bind:this={iframe}
      title={tile.title ?? "Plugin tile"}
      sandbox="allow-scripts"
      referrerpolicy="no-referrer"
      {srcdoc}
      style={frameHeight === null
        ? "height:100%"
        : `height:${Math.max(PLUGIN_MIN_H, frameHeight)}px`}
    ></iframe>
    {#if pluginError}
      <div class="plugin-error" role="alert">{pluginError}</div>
    {/if}
  </div>
{/if}

<style>
  .plugin-tile {
    position: relative;
    height: 100%;
    width: 100%;
    min-height: 240px;
    overflow: auto;
  }
  iframe {
    display: block;
    width: 100%;
    min-height: 40px;
    border: 0;
  }
  .plugin-error {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    padding: 4px 8px;
    font-size: 12px;
    color: var(--saiku-embed-error, #b91c1c);
    background: var(--saiku-embed-tile-bg, #fff);
    border-top: 1px solid var(--saiku-embed-border, #e5e7eb);
    white-space: pre-wrap;
    word-break: break-word;
  }
  .state {
    padding: 12px;
    font-family: system-ui, sans-serif;
    font-size: 13px;
  }
  .state.muted {
    color: var(--saiku-embed-muted, #6b7280);
  }
</style>
