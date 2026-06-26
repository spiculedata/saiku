# Saiku Cloud — Dashboard Design System (Code)

This directory is the **typed entry point** for the dashboard's design system. The full human-readable reference lives at `docs/design-system.md`; this directory is the source of truth for **what's importable** and **how to import it**.

## Quick rules

1. **Import from `$lib/design-system`, not deep paths.**

   ```ts
   // YES
   import { PageHeader, FeedbackBanner, type Tone } from '$lib/design-system';

   // NO
   import PageHeader from '$lib/components/layout/PageHeader.svelte';
   ```

2. **Use semantic tone tokens, not raw Tailwind colors.**

   ```html
   <!-- YES -->
   <p class="text-success">Uploaded.</p>
   <p class="text-destructive">Failed.</p>

   <!-- NO — these don't work in light mode -->
   <p class="text-emerald-300">Uploaded.</p>
   <p class="text-red-200">Failed.</p>
   ```

   The semantic tokens (`--success`, `--warning`, `--destructive`, `--info`) have separate light + dark values defined in `src/routes/layout.css`. Raw Tailwind color utilities only resolve to one shade.

3. **Use the design-system component, not a hand-rolled equivalent.**
   - Need a page header? → `<PageHeader title="…" subtitle="…" />`
   - Need a status callout? → `<FeedbackBanner tone="success">…</FeedbackBanner>`
   - Don't reinvent these inline.

## Currently exported

| Export                 | Source file                     | Use for                                                                                                                   |
| ---------------------- | ------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `PageHeader`           | `./PageHeader.svelte`           | Top of every authed dashboard page (eyebrow + h1 + subtitle + optional back link + optional actions).                     |
| `FeedbackBanner`       | `./FeedbackBanner.svelte`       | Inline success / error / warning / info callouts.                                                                         |
| `Badge`                | `./Badge.svelte`                | Small inline pill for status, role, tier, or eyebrow labels. Six tones × two shapes (square / pill) + uppercase modifier. |
| `EmptyState`           | `./EmptyState.svelte`           | "No ... yet" message. `inline` variant for inside a SectionCard; `card` variant for standalone dashed-border.             |
| `SectionCard`          | `./SectionCard.svelte`          | Bordered card with optional header strip (title + count + actions slot).                                                  |
| `Toast`                | `./Toast.svelte`                | Transient top-right notification. Tone-driven. Caller owns the show/dismiss lifecycle.                                    |
| `FormField`            | `./FormField.svelte`            | Label + input wrapper. Children slot for any control (input, select, textarea). Supports `hint`, `error`, `required`.     |
| `SortableColumnHeader` | `./SortableColumnHeader.svelte` | Clickable table column header with up/down/neutral sort arrow.                                                            |
| `TONES`, `Tone`        | `./tokens.ts`                   | Compile-time vocabulary for status tones.                                                                                 |
| `TONE_CLASSES`         | `./tokens.ts`                   | Tailwind class map per tone — for one-off divs that need tinting.                                                         |
| `SIZES`, `Size`        | `./tokens.ts`                   | `'sm' \| 'md' \| 'lg'` for compound components.                                                                           |

## Adding a new design-system component

1. Drop the Svelte component **here**, in `src/lib/design-system/`.
2. Add a re-export to `index.ts`.
3. Write a `<Name>.stories.svelte` next to it (see `PageHeader.stories.svelte` for the shape — title pattern, theme-toggle compatibility, sensible default args per variation).
4. Add an entry to `docs/design-system.md` documenting props + usage sites.
5. (Optional but encouraged) Refactor at least one usage site in the same PR so the abstraction is grounded.

Domain-specific components (cube authoring, chart renderers, etc.) still live under `src/lib/components/<bucket>/` — only design-system components (the generic vocabulary used across routes) live here.

## Browsing the catalog (Storybook)

Storybook is wired up to render every component in isolation with a light/dark theme toggle and interactive prop controls.

```sh
pnpm storybook        # dev server at http://localhost:6006
pnpm build-storybook  # static export (for CI artifact / deploy)
```

Use the **Theme** toggle in the top toolbar to flip between light + dark; the underlying CSS variables in `src/routes/layout.css` drive the switch.

### What's in the catalog

The sidebar is organised three layers deep:

**Foundation** — tokens. No interactive components; just visual reference.

- `Foundation/Tokens` — Colors (every CSS variable as a swatch), Typography (the heading + body scale in active use), Spacing (gap / padding scale).

**Primitives** — the shadcn-svelte building blocks at `src/lib/components/ui/`. Use these directly when a compound doesn't yet exist.

- `Primitives/Button` — six variants (default, destructive, outline, secondary, ghost, link, text) × five sizes.
- `Primitives/Card` — bordered surface with header / title / description / content composables.
- `Primitives/Input` — text entry; pair with a `<label>` wrapper for the standard form-field shape (no separate Label primitive yet).
- `Primitives/Tooltip` — built on bits-ui; wraps a single trigger.

**Compounds** — design-system compositions one level above the primitives. Each lives at this directory or under `src/lib/components/<bucket>/`; the catalog imports them via the canonical paths.

- `Compounds/PageHeader` — standard authed-page header (eyebrow + h1 + subtitle + back link + actions).
- `Compounds/FeedbackBanner` — inline success / error / warning / info callout. Tone-driven via the `--success` / `--warning` / `--destructive` / `--info` tokens.
- `Compounds/Data sources tabs` — the underline tab strip that joins /connections + /files.

### Adding new stories

Stories live next to their components, named `<Name>.stories.svelte`. The catalog auto-discovers anything matching that pattern under `src/lib/design-system/` (and `src/lib/components/ui/` for primitives). Title prefix determines sidebar grouping — use `Foundation/`, `Primitives/`, or `Compounds/`.

## Adding a new tone or token

1. Add the CSS variable to `src/routes/layout.css` in BOTH the `:root` (light) and `:root[data-theme='dark']` blocks. Both modes must work; no exceptions.
2. Add a `--color-<tone>` binding under `@theme inline` so Tailwind generates `text-<tone>`, `bg-<tone>`, `border-<tone>` utility classes.
3. Add the new tone to the `TONES` union in `tokens.ts`.
4. Add the tone-class triple to `TONE_CLASSES` in `tokens.ts`.
5. Document it in `docs/design-system.md` § Status tones.

## Why this isn't a separate package (yet)

Saiku Cloud is a single repo, single product. Externalising the design system to its own npm package would add publishing overhead with no consumer. If the design system grows to support multiple Saiku-branded products (a marketing site, a docs site, a customer portal), that's the moment to extract.

For now: this directory is the design system. Imports go through `$lib/design-system`. The barrel is the contract.

## Related references

- `docs/design-system.md` — human-readable token map, typography hierarchy, walkthrough class vocabulary, gaps inventory.
- `src/routes/layout.css` — CSS variable source of truth (light + dark).
- `src/lib/components/ui/` — shadcn primitives (Button, Card, Input, Tooltip) that the design system builds on top of.
