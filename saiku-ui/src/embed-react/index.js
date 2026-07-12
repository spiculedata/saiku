/*
 * @concepttocloud/saiku-embed-react — typed React wrapper around the
 * <saiku-embed> custom element (saiku#1432).
 *
 * The custom element already works in React because React 18+ passes
 * unknown attributes straight through to the DOM. This wrapper adds:
 *
 *   - Typed props via the sibling index.d.ts (autocomplete + type-check
 *     in TSX consumers).
 *   - Side-effect registration of the underlying custom element on
 *     import, so consumers write one import and get both the tag AND
 *     the typed component.
 *   - Kebab-case attribute handling so `token` / `render` / `height`
 *     survive React's DOM attribute setter and land on the element in
 *     the shape the shadow root reads.
 *
 * Hand-authored ESM, no build step. The whole runtime is ~50 lines.
 * A parallel TSX draft in src/embed-react/index.tsx is the source of
 * truth for future refactoring — this file is what ships to npm.
 */
import * as React from "react";

// Side-effect import: registers the <saiku-embed> tag globally.
import "@concepttocloud/saiku-embed";

/**
 * Typed React wrapper around the <saiku-embed> custom element.
 * See index.d.ts for the full prop reference.
 */
export function SaikuEmbed(props) {
  const {
    server,
    path,
    kind,
    token,
    render,
    mode,
    height,
    space,
    filter,
    theme,
    style,
    className,
    id,
    "data-testid": dataTestId,
    onLoad,
    onError,
    onSelect,
    onAiQuery,
  } = props;

  const ref = React.useRef(null);

  // The custom element emits CustomEvents (saiku:load / saiku:error /
  // saiku:select / saiku:ai-query) that React props can't bind directly — a
  // JSX `onLoad` doesn't map to the colon-namespaced DOM event. Attach the
  // handlers imperatively so consumers get idiomatic callback props. Each
  // handler is forwarded the event's `detail` payload.
  React.useEffect(() => {
    const node = ref.current;
    if (!node) return undefined;
    const pairs = [
      ["saiku:load", onLoad],
      ["saiku:error", onError],
      ["saiku:select", onSelect],
      ["saiku:ai-query", onAiQuery],
    ].filter(([, fn]) => typeof fn === "function");
    const listeners = pairs.map(([type, fn]) => {
      const l = (e) => fn(e.detail);
      node.addEventListener(type, l);
      return [type, l];
    });
    return () => {
      for (const [type, l] of listeners) node.removeEventListener(type, l);
    };
  }, [onLoad, onError, onSelect, onAiQuery]);

  // React 18+ passes string attributes verbatim to custom elements. The
  // one gotcha is that undefined-valued attrs would land as the string
  // "undefined" — hence the explicit spread guarded by each present
  // value. Empty strings are legal (server="" = same-origin) so we test
  // for undefined specifically rather than falsy.
  const attrs = { ref };
  if (server !== undefined) attrs.server = server;
  if (path !== undefined) attrs.path = path;
  if (kind !== undefined) attrs.kind = kind;
  if (token !== undefined) attrs.token = token;
  if (render !== undefined) attrs.render = render;
  if (mode !== undefined) attrs.mode = mode;
  if (height !== undefined) attrs.height = height;
  if (space !== undefined) attrs.space = space;
  // `filter` is a JSON array on the wire; accept either a ready string or an
  // array/object and serialise it so React consumers can pass a value.
  if (filter !== undefined) attrs.filter = typeof filter === "string" ? filter : JSON.stringify(filter);
  if (theme !== undefined) attrs.theme = theme;
  if (id !== undefined) attrs.id = id;
  if (dataTestId !== undefined) attrs["data-testid"] = dataTestId;
  if (style !== undefined) attrs.style = style;
  if (className !== undefined) attrs.className = className;

  return React.createElement("saiku-embed", attrs);
}

/**
 * Server-side helper: mint an embed token via
 * POST /rest/saiku/api/embed/tokens. See index.d.ts for the full
 * options reference.
 *
 * Never call this from the browser — the admin credentials it needs
 * must never ship client-side.
 */
export async function mintEmbedToken(opts) {
  const doFetch = opts.fetch !== undefined ? opts.fetch : fetch;
  const body = {
    resourceKind: opts.resourceKind,
    resourcePath: opts.resourcePath,
  };
  if (opts.ttlHours !== undefined) body.ttlHours = opts.ttlHours;
  if (opts.label !== undefined) body.label = opts.label;
  const url = opts.server.replace(/\/+$/, "") + "/rest/saiku/api/embed/tokens";
  const res = await doFetch(url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: opts.authorization,
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    throw new Error(`mintEmbedToken: HTTP ${res.status} ${res.statusText}`);
  }
  const json = await res.json();
  if (!json.token) {
    throw new Error("mintEmbedToken: server response missing 'token'");
  }
  return { token: json.token, expiresAt: json.expiresAt !== undefined ? json.expiresAt : 0 };
}
