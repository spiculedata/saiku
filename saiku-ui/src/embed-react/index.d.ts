/**
 * @concepttocloud/saiku-embed-react — type declarations.
 *
 * Ships alongside index.js. Consumers who use TypeScript see typed
 * props on <SaikuEmbed>; consumers who use plain JS see the runtime
 * behaviour with no type layer at all.
 */

import type * as React from 'react';

/** Which flavour of saved resource the embed loads. */
export type SaikuEmbedKind = 'query' | 'dashboard' | 'ai';

/** Render mode for `kind="query"` embeds. */
export type SaikuEmbedRender = 'table' | 'matrix' | 'chart' | 'kpi';

/** Chart type when `render="chart"`. */
export type SaikuEmbedChartMode = 'bar' | 'line' | 'pie';

/** Colour theme. Unset keeps the original light palette. */
export type SaikuEmbedTheme = 'light' | 'dark' | 'auto';

/** One slicer override applied at embed time via the `filter` prop. Mirrors the
 *  server's AiFilterSelection — empty `members` clears that axis. */
export interface SaikuEmbedFilter {
	dimension: string;
	hierarchy?: string | null;
	level: string;
	members: string[];
}

/** Detail payloads carried by the element's CustomEvents. */
export interface SaikuEmbedLoadDetail {
	kind: string;
	rows: number;
}
export interface SaikuEmbedErrorDetail {
	message: string;
}
export interface SaikuEmbedSelectDetail {
	row: Record<string, { value: number | null; formatted: string; unit?: string }>;
}
export interface SaikuEmbedAiQueryDetail {
	question: string;
	degraded: boolean;
}

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

	/**
	 * For `kind="ai"`: an Agent Space persona id (saiku#1440). Scopes the ask
	 * server-side — the persona's system prompt, skill filter, and cube allowlist
	 * apply. Can only narrow what the pinned cube exposes.
	 */
	space?: string;

	/**
	 * For `kind="query"`: slicer overrides applied at embed time. Pass an array of
	 * {@link SaikuEmbedFilter} (serialised for you) or a ready JSON string.
	 */
	filter?: SaikuEmbedFilter[] | string;

	/** Colour theme. Defaults to the light palette when unset. */
	theme?: SaikuEmbedTheme;

	/** Fired after a query/matrix/kpi surface finishes loading. */
	onLoad?: (detail: SaikuEmbedLoadDetail) => void;

	/** Fired when a query load fails (friendly message only). */
	onError?: (detail: SaikuEmbedErrorDetail) => void;

	/** Fired when a table row is clicked (`render="table"`). */
	onSelect?: (detail: SaikuEmbedSelectDetail) => void;

	/** Fired after an AI ask resolves (`kind="ai"`). */
	onAiQuery?: (detail: SaikuEmbedAiQueryDetail) => void;

	/** Standard React style prop — applied to the custom element itself. */
	style?: React.CSSProperties;

	/** Standard React className prop — applied to the custom element itself. */
	className?: string;

	/** Passed through to the DOM node — useful for e2e selectors. */
	id?: string;

	/** Passed through to the DOM node. */
	'data-testid'?: string;
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
export declare function mintEmbedToken(opts: MintEmbedTokenOptions): Promise<MintEmbedTokenResult>;

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
	space?: string;
	filter?: string;
	theme?: SaikuEmbedTheme;
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
			'saiku-embed': React.DetailedHTMLProps<SaikuEmbedElementAttributes, HTMLElement>;
		}
	}
}

declare module 'react' {
	namespace JSX {
		interface IntrinsicElements {
			'saiku-embed': React.DetailedHTMLProps<SaikuEmbedElementAttributes, HTMLElement>;
		}
	}
}
