import { browser } from "$app/environment";

type AuthListener = (status: number, path: string) => void;
const listeners = new Set<AuthListener>();

export function onAuthFailure(fn: AuthListener): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function notify(status: number, path: string): void {
  for (const l of listeners) l(status, path);
}

/**
 * Wrap global fetch so any authenticated call that returns 401 / 403 can
 * surface through the SessionErrorModal. Called once at app boot.
 */
export function installAuthInterceptor(): void {
  if (!browser) return;
  const original = globalThis.fetch;
  if ((original as { __saikuPatched?: boolean }).__saikuPatched) return;
  const patched: typeof fetch = async (input, init) => {
    const res = await original(input, init);
    if (res.status === 401 || res.status === 403) {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
      // Only watch /rest/saiku/* — other 401s are someone else's problem.
      if (url.includes("/rest/saiku/") && !url.includes("/rest/saiku/session")) {
        notify(res.status, url);
      }
    }
    return res;
  };
  (patched as { __saikuPatched?: boolean }).__saikuPatched = true;
  globalThis.fetch = patched;
}
