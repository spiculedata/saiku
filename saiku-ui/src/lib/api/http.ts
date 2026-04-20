import { browser } from "$app/environment";

type AuthListener = (status: number, path: string) => void;
type ResumeListener = () => void;
const listeners = new Set<AuthListener>();
const resumeListeners = new Set<ResumeListener>();

export function onAuthFailure(fn: AuthListener): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/**
 * Subscribe to session-resumed events. Fired after the user successfully
 * re-authenticates from the SessionErrorModal so callers that had an
 * in-flight request fail with 401/403 can replay it.
 */
export function onSessionResumed(fn: ResumeListener): () => void {
  resumeListeners.add(fn);
  return () => resumeListeners.delete(fn);
}

/**
 * Signal that the user just re-authenticated. Any caller that stashed a
 * retry thunk via {@link registerPendingOp} will have it replayed once.
 */
export function notifySessionResumed(): void {
  const ops = Array.from(pendingOps);
  pendingOps.clear();
  for (const l of resumeListeners) {
    try { l(); } catch { /* swallow */ }
  }
  for (const op of ops) {
    try { void op(); } catch { /* swallow */ }
  }
}

type PendingOp = () => void | Promise<void>;
const pendingOps = new Set<PendingOp>();

/**
 * Register a thunk to be replayed after a successful session resume.
 * Returns an unregister function; call it if the operation completes or is
 * cancelled before the user re-authenticates.
 */
export function registerPendingOp(op: PendingOp): () => void {
  pendingOps.add(op);
  return () => pendingOps.delete(op);
}

/** True if at least one caller is currently waiting on session-resume. */
export function hasPendingOps(): boolean {
  return pendingOps.size > 0;
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
