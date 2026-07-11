/**
 * @concepttocloud/saiku-embed-react — type declarations.
 *
 * Ships alongside index.js. Consumers who use TypeScript see typed
 * props on <SaikuEmbed>; consumers who use plain JS see the runtime
 * behaviour with no type layer at all.
 */

import type * as React from "react";

/** Which flavour of saved resource the embed loads. */
export type SaikuEmbedKind = "query" | "dashboard" | "ai";

/** Render mode for `kind="query"` embeds. */
export type SaikuEmbedRender = "table" | "matrix" | "chart";

/** Chart type when `render="chart"`. */
export type SaikuEmbedChartMode = "bar" | "line" | "pie";

/**
 * Props for the {@link SaikuEmbed} React component.
 *
 * Every prop maps 1:1 to an attribute the underlying custom element
 * reads (`server`, `path`, `token`, …). Leaving optional props unset
 * defers to the element's built-in defaults.
 */
export interface SaikuEmbedProps {
  /**
   * Origin of the Saiku launcher, e.g. `https://demo.saiku.bi`. Leave
   * undefined for same-origin embeds — the element resolves the fetch
   * base against the current page.
   */
  server?: string;

  /**
   * The resource identifier:
   *
   * - `kind="query"`: repository path to a saved query
   *   (`homes/admin/Trend.saiku`).
   * - `kind="dashboard"`: repository path to a dashboard
   *   (`homes/admin/exec.saikudash`).
   * - `kind="ai"`: cube ref (`connection/catalog/schema/cubeName`).
   */
  path: string;

  /** Defaults to `"query"`. */
  kind?: SaikuEmbedKind;

  /**
   * Embed token minted server-side via `POST /rest/saiku/api/embed/tokens`.
   * Omit for resources that are publicly granted.
   */
  token?: string;

  /** For `kind="query"`. Defaults to `"table"`. */
  render?: SaikuEmbedRender;

  /** For `render="chart"`. Defaults to `"bar"`. */
  mode?: SaikuEmbedChartMode;

  /** CSS height of the rendered surface. Defaults to `"400px"`. */
  height?: string;

  /** Standard React style prop — applied to the custom element itself. */
  style?: React.CSSProperties;

  /** Standard React className prop — applied to the custom element itself. */
  className?: string;

  /** Passed through to the DOM node — useful for e2e selectors. */
  id?: string;

  /** Passed through to the DOM node. */
  "data-testid"?: string;
}

/**
 * Typed React wrapper around the `<saiku-embed>` custom element.
 *
 * ```tsx
 * import { SaikuEmbed } from "@concepttocloud/saiku-embed-react";
 *
 * function Dashboard() {
 *   return (
 *     <SaikuEmbed
 *       server="https://demo.saiku.bi"
 *       token={token}
 *       path="homes/admin/Sales.saiku"
 *       render="chart"
 *       mode="bar"
 *       height="480px"
 *     />
 *   );
 * }
 * ```
 *
 * The wrapper is deliberately thin — the custom element handles fetch,
 * render, error surfaces, and the shadow DOM. This component's whole
 * job is giving TypeScript and IDEs something to autocomplete against.
 */
export declare function SaikuEmbed(props: SaikuEmbedProps): React.ReactElement;

/**
 * Options for {@link mintEmbedToken}.
 */
export interface MintEmbedTokenOptions {
  /** Base URL of the launcher (`https://saiku.example.com`). */
  server: string;
  /** Value for the `Authorization` header (`Basic …` or `Bearer …`). */
  authorization: string;
  /** `"query"` | `"dashboard"` | `"ai"`. */
  resourceKind: SaikuEmbedKind;
  /** Path (for query/dashboard) or cube ref (for ai). */
  resourcePath: string;
  /** Optional token lifetime; server default is 72h. */
  ttlHours?: number;
  /** Optional human label the admin UI shows next to the token. */
  label?: string;
  /** Optional fetch impl for testing / non-browser runtimes. */
  fetch?: typeof fetch;
}

/**
 * Result of {@link mintEmbedToken}.
 */
export interface MintEmbedTokenResult {
  /** Opaque token to hand to `<SaikuEmbed token=…>`. */
  token: string;
  /**
   * Server-issued expiry (ms since epoch). Zero when the server didn't
   * include one in the response (e.g. static grants).
   */
  expiresAt: number;
}

/**
 * Server-side ergonomics: mint an embed token for a resource before
 * rendering `<SaikuEmbed token=…>`. Call this from your Node / edge
 * function — never from browser code, because the `authorization`
 * header must not ship client-side.
 *
 * ```ts
 * const { token } = await mintEmbedToken({
 *   server: "https://saiku.example.com",
 *   authorization: "Basic " + Buffer.from("admin:admin").toString("base64"),
 *   resourceKind: "query",
 *   resourcePath: "homes/admin/Trend.saiku",
 *   ttlHours: 24,
 *   label: "Marketing landing",
 * });
 * ```
 */
export declare function mintEmbedToken(
  opts: MintEmbedTokenOptions,
): Promise<MintEmbedTokenResult>;

/**
 * Attribute shape shared by the raw `<saiku-embed>` element and the
 * {@link SaikuEmbed} React component. Exported so consumers can build
 * their own JSX augmentations against the same union.
 */
export type SaikuEmbedElementAttributes = React.HTMLAttributes<HTMLElement> & {
  server?: string;
  path?: string;
  kind?: SaikuEmbedKind;
  token?: string;
  render?: SaikuEmbedRender;
  mode?: SaikuEmbedChartMode;
  height?: string;
};

/**
 * Ambient JSX augmentation so `<saiku-embed>` inside JSX is typed by
 * React's typechecker. Consumers who prefer the raw element (mixing
 * with slot content, escape hatch) get autocomplete on the same prop
 * set as {@link SaikuEmbedProps}.
 *
 * Augments both the global JSX namespace (React 17/18) and
 * React.JSX (React 19+) so the same declaration works across the
 * transition.
 */
declare global {
  namespace JSX {
    interface IntrinsicElements {
      "saiku-embed": React.DetailedHTMLProps<SaikuEmbedElementAttributes, HTMLElement>;
    }
  }
}

declare module "react" {
  namespace JSX {
    interface IntrinsicElements {
      "saiku-embed": React.DetailedHTMLProps<SaikuEmbedElementAttributes, HTMLElement>;
    }
  }
}
