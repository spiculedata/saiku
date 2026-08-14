# saiku-ui — design system

The design system itself is **not in this directory**. It lives in
`@concepttocloud/saiku-design-system`, whose source is at `saiku-ui/design-system/`
in this repo and which is published to npm under Apache-2.0. saiku-ui and the Saiku
Cloud dashboard both consume it, so there's one implementation instead of two copies
that drift apart.

Read `saiku-ui/design-system/README.md` for the component list, the token rules, and
the tone-alias contrast reasoning.

## What IS in this directory

- **`index.ts`** — a barrel re-exporting the package. Import from `$lib/design-system`,
  not from the package directly, so there's one place to shim if the package ever needs
  adapting for saiku-ui.
- **`*.stories.svelte`** — the Storybook catalogue. Stories live in the app, not the
  package: they're how we browse the system, and keeping them here means a story can
  freely pull in saiku-ui-only components for context.
- **`primitives/`** — saiku-ui's own extra primitives, not part of the shared system.

The stories also cover components that are genuinely saiku-ui's (`Modal`,
`ContextMenu`, `LocalePicker`, `Skeleton`, `ModalActions`, `ModalModeSwitch`) — those
stay in `$lib/components/` and `$lib/modals/`.

## Rules

1. **Import from the barrel.**

   ```ts
   import { PageHeader, FeedbackBanner, type Tone } from '$lib/design-system';
   import { Button } from '$lib/components/ui';
   ```

2. **Use semantic tone tokens, never raw Tailwind colours.** `text-success`, not
   `text-emerald-300` — raw shades only resolve in one theme. The ESLint rule at
   `eslint.config.js` blocks them outside this folder, and `npm run lint:tokens`
   catches the rest.

3. **Changing a shared component or token means changing the package**, at
   `saiku-ui/design-system/src/lib/`. It's a workspace, so an edit there is picked up by
   `npm run dev` after `npm run build --workspace @concepttocloud/saiku-design-system`.
   Remember it ships to saiku-cloud too.

4. **App-specific components don't go in the package.** Anything that knows about cubes,
   MDX, queries or dashboards belongs in `$lib/components/` or `$lib/views/`.

## Browsing the catalogue

```sh
npm run storybook        # dev server at http://localhost:6006
npm run build-storybook  # static export
```

Use the **Theme** toggle in the toolbar to flip light/dark — the package's `tokens.css`
drives both.
