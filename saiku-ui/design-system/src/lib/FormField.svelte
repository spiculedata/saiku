<!--
  FormField — standard label + input wrapper.

  Replaces the ~22 inline copies of `<label class="flex flex-col gap-1
  text-sm"><span class="font-medium">…</span><input … /></label>` across
  the dashboard.

  Caller passes the input element (or any control) as the `children`
  snippet. The wrapper only owns the label + optional hint + optional
  error.

  Why the input lives in the snippet rather than as a prop: form
  controls vary too much (text, select, password, file, range) to
  paper over with a single `type` enum. Letting the caller pass any
  control keeps the wrapper honest.

  Variation:
    - label:    string label (rendered with font-medium)
    - hint:     small muted-foreground hint shown below the input
    - error:    error message; if present, replaces the hint and uses
                the destructive tone
    - required: appends a "*" after the label
    - testid:   data-testid passthrough
-->
<script lang="ts">
	import type { Snippet } from 'svelte';

	interface Props {
		label: string;
		hint?: string;
		error?: string;
		required?: boolean;
		testid?: string;
		children: Snippet;
	}

	let { label, hint, error, required = false, testid, children }: Props = $props();
</script>

<!--
	Label treatment matches saiku-ui's legacy `.field__label` — 13px (text-sm
	resolves to --fs-sm through the token bridge), normal weight, --fg-muted.
	NOT a bolder full-strength label, which is what this component used to have.

	The reason is migration cost, not taste. `.field` is on 117 controls across
	42 files and this component on 10; matching the incumbent makes replacing
	`.field` a visually neutral swap instead of a 117-control form redesign, and
	keeps 2.0.0's visual change to one thing (the button scale) so a regression
	is attributable. If the bolder label is wanted, change it here deliberately
	once the migration has landed — not as a side effect of it.
-->
<label class="flex flex-col gap-1 text-sm" data-testid={testid}>
	<span class="text-fg-muted">
		{label}{#if required}<span class="ml-0.5 text-destructive" aria-hidden="true">*</span>{/if}
	</span>
	{@render children()}
	{#if error}
		<span class="text-xs text-destructive">{error}</span>
	{:else if hint}
		<span class="text-xs text-muted-foreground">{hint}</span>
	{/if}
</label>
