function readCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp("(?:^|; )" + name + "=([^;]*)"));
  return m ? decodeURIComponent(m[1]) : null;
}
export async function api(path: string, opts: RequestInit = {}): Promise<Response> {
  const method = (opts.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = { ...(opts.headers as Record<string, string>) };
  if (opts.body && !headers["Content-Type"]) headers["Content-Type"] = "application/json";
  if (method !== "GET" && method !== "HEAD") {
    const tok = readCookie("XSRF-TOKEN");
    if (tok) headers["X-XSRF-TOKEN"] = tok;
  }
  return fetch(path, { ...opts, method, headers, credentials: "include" });
}
export async function apiJson<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const res = await api(path, opts);
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${path}`);
  return (await res.json()) as T;
}
