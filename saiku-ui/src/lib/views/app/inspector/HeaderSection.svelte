<script lang="ts">
  /* Inspector → Header. The branded top bar: app name + accent word, eyebrow,
   * context pill (label/value), and live badge. Writes via appDoc. */
  import { appDoc } from "$lib/stores/appDoc.svelte";

  const app = $derived(appDoc.current);
  const header = $derived(app?.header ?? {});
  const pill = $derived(header.contextPill ?? { label: "", value: "" });

  const setHeader = (patch: Record<string, unknown>) => appDoc.updateHeader(patch);
  const val = (e: Event) => (e.currentTarget as HTMLInputElement).value;
  /** Empty string → drop the field (so an empty box means "off"). */
  const opt = (v: string) => (v.trim() === "" ? undefined : v);
</script>

<div class="insp-section">
  <div class="insp-label">Wordmark</div>
  <label class="insp-row"><span>App name</span>
    <input class="insp-input" value={app?.name ?? ""} oninput={(e) => appDoc.rename(val(e))} />
  </label>
  <label class="insp-row"><span>Accent word</span>
    <input class="insp-input" placeholder="e.g. Mart" value={header.wordmarkAccent ?? ""}
      oninput={(e) => setHeader({ wordmarkAccent: opt(val(e)) })} />
  </label>
  <p class="insp-hint">The part of the name shown in the brand-mark colour (from Theme). Leave blank for a single-tone wordmark.</p>
  <label class="insp-row"><span>Eyebrow</span>
    <input class="insp-input" placeholder="e.g. Store Intelligence" value={header.eyebrow ?? ""}
      oninput={(e) => setHeader({ eyebrow: opt(val(e)) })} />
  </label>
</div>

<div class="insp-section">
  <div class="insp-label">Context pill</div>
  <label class="insp-row"><span>Label</span>
    <input class="insp-input" placeholder="e.g. Store" value={pill.label}
      oninput={(e) => setHeader({ contextPill: { label: val(e), value: pill.value } })} />
  </label>
  <label class="insp-row"><span>Value</span>
    <input class="insp-input" placeholder="e.g. Portland #14" value={pill.value}
      oninput={(e) => setHeader({ contextPill: { label: pill.label, value: val(e) } })} />
  </label>
  <button type="button" class="insp-addbtn" onclick={() => setHeader({ contextPill: undefined })}>Remove pill</button>
</div>

<div class="insp-section">
  <div class="insp-label">Live badge</div>
  <label class="insp-row"><span>Text</span>
    <input class="insp-input" placeholder="e.g. Live · Saiku" value={header.liveBadge ?? ""}
      oninput={(e) => setHeader({ liveBadge: opt(val(e)) })} />
  </label>
  <p class="insp-hint">Rendered with a ● dot. Leave blank to hide.</p>
</div>
