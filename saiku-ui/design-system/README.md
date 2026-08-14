# @concepttocloud/saiku-design-system

The design system shared by **Saiku** (`saiku-ui`) and **Saiku Cloud** (`dashboard`) — CSS
design tokens, a Tailwind v4 theme bridge, and the Svelte 5 components built on top of them.

Apache-2.0. Published from the open-source [saiku](https://github.com/spiculedata/saiku)
repo so anyone building against Saiku can use the same vocabulary the product uses.

```sh
npm install @concepttocloud/saiku-design-system
```

## What's in it

| Entry | Contents |
| --- | --- |
| `@concepttocloud/saiku-design-system` | Compounds: `PageHeader`, `FeedbackBanner`, `Badge`, `EmptyState`, `SectionCard`, `Toast`, `FormField`, `SortableColumnHeader`, plus the `TONES` / `TONE_CLASSES` / `SIZES` vocabulary. |
| `@concepttocloud/saiku-design-system/ui` | shadcn-shaped primitives: `Button` (+ `buttonVariants`), `Card` group, `Input`, `Tooltip`. |
| `@concepttocloud/saiku-design-system/tokens.css` | The token layer — every colour, space, radius, type, weight, shadow and duration token, with light + dark values. |
| `@concepttocloud/saiku-design-system/tailwind.css` | Tailwind v4 entry: `@theme inline` bridge mapping the tokens onto Tailwind's namespace, plus the `dark:` custom variant. |

## Usage

Import the CSS once, at your app's root, **tokens first**:

```css
@import "@concepttocloud/saiku-design-system/tokens.css";
@import "@concepttocloud/saiku-design-system/tailwind.css";
```

Then use the components and the token utilities:

```svelte
<script>
  import { PageHeader, FeedbackBanner } from '@concepttocloud/saiku-design-system';
  import { Button } from '@concepttocloud/saiku-design-system/ui';
</script>

<PageHeader title="Connections" subtitle="Where Saiku reads your data from" />
<FeedbackBanner tone="success">Saved.</FeedbackBanner>
<Button variant="outline">Cancel</Button>
```

`tailwind.css` deliberately imports `tailwindcss/theme.css` and `tailwindcss/utilities.css`
but **not** preflight — both consuming apps carry their own base reset and would lose
box-sizing / heading assumptions if preflight landed on top. If you're starting fresh, add
`@import "tailwindcss/preflight.css" layer(base);` yourself.

### Cascade layers are load-bearing

`tailwind.css` declares `@layer theme, base, components, utilities;`. Any type-selector rule
you write (`a { color: ... }`) must live in `@layer base`, or it will beat every Tailwind
utility regardless of specificity — that's the cascade-layers spec, and an unlayered
`a { color: var(--accent) }` once ate `text-primary-foreground` on every anchor-as-button
in saiku-ui.

## Tone tokens

Use the semantic tones, never raw Tailwind colours — raw shades only resolve to one value
and break in the other theme:

```svelte
<p class="text-success">Uploaded.</p>     <!-- yes -->
<p class="text-emerald-300">Uploaded.</p> <!-- no -->
```

The tone aliases (`--success`, `--warning`, `--info`, `--destructive`) point at the **-text
(600)** weight of their ramp, not the -action (500) weight. 600 clears AA as body text on a
light surface where 500 does not, and white-on-600 clears AA for the `bg-*` fill where
white-on-500 sits at roughly 2.8:1.

> **Dark-mode caveat on `bg-*` tone fills.** In dark mode the ramp inverts and `-text`
> resolves to the *300* step, which is light. `text-success` gets better contrast on a dark
> surface, but `bg-success` becomes a light fill — so pair it with `text-success-foreground`
> (which resolves through `--on-success-action`) rather than assuming white reads on it.
> Tone fills that need a dark-mode-safe background should use the `-soft` variants
> (`bg-success-soft`), which are built for exactly that.

## Adding a component

1. Drop the `.svelte` file in `src/lib/`.
2. Re-export it from `src/lib/index.ts` (primitives from `src/lib/ui/index.ts`).
3. Add a story in saiku-ui at `src/lib/design-system/<Name>.stories.svelte` — Storybook is
   the catalogue and lives in the app, not the package.
4. Keep it generic. Anything that knows about cubes, MDX, connections, or billing belongs in
   the consuming app, not here.

## Building

```sh
npm install
npm run build     # svelte-package: src/lib → dist (JS + .d.ts + CSS passthrough)
npm run check     # svelte-check
```

`dist/` is generated and git-ignored.

## Releasing

The package carries **its own version**, starting at 1.0.0 and independent of both the
saiku reactor (4.x) and saiku-ui (3.x) — it moves on its own cadence, because coupling it
to product releases would mean saiku-cloud waits for a full Saiku release every time a
token changes. (The sibling `@concepttocloud/saiku-embed*` packages deliberately mirror
saiku-ui's version because they *are* saiku-ui's embed bundle; this one isn't, so its
version number is its own and actually means something about design-system compatibility.)

To cut a release: bump `version` here and merge to `development`. The
`.github/workflows/design-system.yml` workflow builds, type-checks, verifies every export
target exists, then publishes. It checks the registry first and skips cleanly if the
version is already out, so merging a package change without a version bump is a no-op
rather than a failure. `workflow_dispatch` offers a `dry_run` input to build and verify
without publishing.
