import { authHeader } from "./authHeader";
import type { Credential } from "./types";

export interface ServerCtx { baseUrl: string; credential: Credential | null; }

export async function api(ctx: ServerCtx, path: string, opts: RequestInit = {}): Promise<Response> {
  const headers: Record<string, string> = { ...(opts.headers as Record<string, string>) };
  if (opts.body && !headers["Content-Type"]) headers["Content-Type"] = "application/json";
  const a = authHeader(ctx.credential);
  if (a) headers["Authorization"] = a;
  // cross-origin: token auth in Authorization header, NOT cookies
  return fetch(ctx.baseUrl + path, { ...opts, headers, credentials: "omit" });
}

export async function apiJson<T>(ctx: ServerCtx, path: string, opts: RequestInit = {}): Promise<T> {
  const res = await api(ctx, path, opts);
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${path}`);
  return (await res.json()) as T;
}
