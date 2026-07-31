/**
 * Client-safe gateway error-message helper (saiku-cloud#1043).
 *
 * The precedence "gateway `message`, then its `error` code, then a generic
 * HTTP-status fallback" (saiku-cloud#852) was copy-pasted inline across
 * several CLIENT surfaces (`.svelte` components + browser fetch handlers).
 * Lifted here so the precedence lives in one place.
 *
 * IMPORTANT: this module MUST stay client-safe — no `$lib/server/*` and no
 * `$env/dynamic/private` imports — because it is imported into `.svelte`
 * components that run in the browser. The server-side twin is
 * `readGatewayError(resp)` in `$lib/server/gateway`, which takes a raw
 * `Response` and parses it; this one takes an already-parsed body + status
 * so it can be used synchronously after a caller's own `resp.json()`.
 *
 * @param status - the HTTP status of the failed response.
 * @param body - the parsed JSON body (or null/undefined for a non-JSON /
 *   empty body).
 * @param fallback - optional surface-specific message used when the body
 *   carries neither `message` nor `error`. Defaults to a generic
 *   `Request failed (HTTP <status>)`. Callers pass their existing wording
 *   here so migrating an inline copy is behaviour-preserving.
 */
export function readGatewayErrorMessage(
  status: number,
  body: { message?: string; error?: string } | null | undefined,
  fallback?: string,
): string {
  return (
    body?.message ??
    body?.error ??
    fallback ??
    `Request failed (HTTP ${status})`
  );
}
