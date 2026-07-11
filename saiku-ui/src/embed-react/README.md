# `@concepttocloud/saiku-embed-react`

Typed React wrapper around the [`<saiku-embed>`](https://www.npmjs.com/package/@concepttocloud/saiku-embed) custom element. Same runtime, React types, single-import ergonomics.

## Install

```bash
npm install @concepttocloud/saiku-embed-react @concepttocloud/saiku-embed react
```

The package peer-depends on **React 18+**. The base `@concepttocloud/saiku-embed` is a runtime dependency and gets pulled in automatically.

## Use

```tsx
import { SaikuEmbed } from "@concepttocloud/saiku-embed-react";

function Dashboard({ token }: { token: string }) {
  return (
    <SaikuEmbed
      server="https://demo.saiku.bi"
      token={token}
      path="homes/admin/Sales.saiku"
      render="chart"
      mode="bar"
      height="480px"
    />
  );
}
```

Importing the package has the side effect of registering the underlying custom element — you don't need a separate `import "@concepttocloud/saiku-embed"` unless you also want the tag available outside of the React tree.

## Props

| Prop        | Type                                | Default    | Notes                                                                                       |
|-------------|-------------------------------------|------------|---------------------------------------------------------------------------------------------|
| `server`    | `string`                            | _optional_ | Origin of the Saiku launcher. Omit for same-origin embeds.                                  |
| `path`      | `string`                            | _required_ | Query path / dashboard path / cube ref depending on `kind`.                                 |
| `kind`      | `"query" \| "dashboard" \| "ai"`    | `"query"`  | Selects the embed flavour.                                                                  |
| `token`     | `string`                            | _optional_ | Embed token minted server-side. Omit for public grants.                                     |
| `render`    | `"table" \| "matrix" \| "chart"`    | `"table"`  | Only meaningful for `kind="query"`.                                                         |
| `mode`      | `"bar" \| "line" \| "pie"`          | `"bar"`    | Only meaningful for `render="chart"`.                                                       |
| `height`    | `string`                            | `"400px"`  | CSS height of the rendered surface.                                                         |
| `style`     | `React.CSSProperties`               | _optional_ | Standard React style prop — applied to the custom element itself.                           |
| `className` | `string`                            | _optional_ | Standard React className prop.                                                              |
| `id`        | `string`                            | _optional_ | Passed through for e2e selectors.                                                           |

Full type declarations ship in `index.d.ts`.

## Rendering as a hierarchical matrix (v3.19)

Matrix mode preserves the row / column axis structure — measures on
columns, dimension members on rows — instead of flattening to a single
row-key map like `render="table"` does. Useful for pivot-style reports.

```tsx
<SaikuEmbed
  server="https://saiku.example.com"
  token={token}
  path="homes/admin/Sales.saiku"
  render="matrix"
  height="500px"
/>
```

## An AI ask widget over a cube (v3.19, `kind="ai"`)

Point the token at a cube (rather than a saved query) and drop in a
plain-English ask box. Behind the scenes it POSTs to
`/rest/saiku/api/embed/ai/{cubeId}/ask`, which runs the question through
the server's configured LLM provider under the pinned owner's data
scope. Requires an AI-kind token (see "Minting a token" below).

```tsx
<SaikuEmbed
  server="https://saiku.example.com"
  token={token}
  kind="ai"
  path="foodmart/FoodMart/FoodMart/Sales"
  height="240px"
/>
```

## A saved dashboard

```tsx
<SaikuEmbed
  server="https://saiku.example.com"
  token={token}
  kind="dashboard"
  path="homes/admin/exec.saikudash"
  height="700px"
/>
```

## Anonymous public embed

If the resource is marked publicly embeddable on the server (see
"Public grants" in the base package README), omit the token entirely:

```tsx
<SaikuEmbed
  server="https://saiku.example.com"
  path="shared/public-chart.saiku"
  render="chart"
/>
```

## Minting a token from your server

`mintEmbedToken()` is a Node / edge-function helper for the very
common case of minting an embed token on behalf of an end user before
rendering `<SaikuEmbed>`. **Never call this from browser code** — the
admin credentials it needs must never ship client-side.

```ts
// app/api/mint-embed-token/route.ts (Next.js App Router example)
import { mintEmbedToken } from "@concepttocloud/saiku-embed-react";

export async function POST(request: Request) {
  const auth = "Basic " + Buffer.from(
    `${process.env.SAIKU_USER}:${process.env.SAIKU_PASS}`,
  ).toString("base64");

  const { token, expiresAt } = await mintEmbedToken({
    server: process.env.SAIKU_URL!,
    authorization: auth,
    resourceKind: "query",
    resourcePath: "homes/admin/Sales.saiku",
    ttlHours: 24,
    label: "Public marketing page",
  });

  return Response.json({ token, expiresAt });
}
```

The client then fetches the minted token and drops it into the
`token` prop.

## TypeScript users: JSX autocomplete on the raw tag

The package augments `JSX.IntrinsicElements["saiku-embed"]` so the raw
custom element gets the same typed prop set as `<SaikuEmbed>`. Use
whichever you prefer:

```tsx
// Typed React component
<SaikuEmbed server="…" token={token} path="…" render="chart" />

// Or the raw custom element (also typed)
<saiku-embed server="…" token={token} path="…" render="chart" />
```

## Styling

The embed lives inside an
[open shadow root](https://developer.mozilla.org/docs/Web/API/ShadowRoot),
so host page CSS can't leak in and vice versa. Recolour via CSS custom
properties on the wrapper:

```tsx
<SaikuEmbed
  server="…"
  token={token}
  path="…"
  style={{
    "--saiku-embed-fg": "#0f172a",
    "--saiku-embed-bg": "transparent",
    "--saiku-embed-border": "#cbd5e1",
    "--saiku-embed-header-bg": "#f1f5f9",
    "--saiku-embed-tile-bg": "#ffffff",
    "--saiku-embed-row-hover": "#e2e8f0",
    "--saiku-embed-negative": "#b91c1c",
  } as React.CSSProperties}
/>
```

The full list of themable variables is documented in the base
package's README.

## Security model

- **Header-only token transport.** The token travels as
  `X-Saiku-Embed-Token`. Never a `?token=` query parameter.
- **Server-side authoritative.** Tokens are opaque random 256-bit ids
  with no embedded claims; the server looks them up on every request.
  Revocation takes effect on the very next request.
- **Per-resource scope.** A token pins exactly one query, dashboard,
  or cube. Replaying it against any other resource returns the same
  opaque `EMBED_INVALID` 401.
- **Cross-origin cookie isolation.** The embed sends
  `credentials: "omit"`, so the host page's Saiku session cookie (if
  the user happens to be logged in) doesn't flow with embed reads.

## Bundle size

Wrapper: ~1 KB gzipped. Base custom element: ~213 KB gzipped (Svelte 5
custom-element runtime + ECharts + the embed renderers). React itself
is a peer dep so it's not counted.

## Version

The package's version tracks the base `@concepttocloud/saiku-embed`
release one-to-one. `3.19.0` of this package uses `3.19.0` of the base;
never mix major versions across the two.

## License

Apache-2.0. Same as the rest of Saiku.
