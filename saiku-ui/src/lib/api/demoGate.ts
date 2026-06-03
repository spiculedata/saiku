/*
 * Client for the demo email-gate endpoints (saiku#1029) at
 * /rest/saiku/demo/gate. These run before login (anonymous) and are carved out
 * of the http.ts auth interceptor — their 400/401 responses are expected flow,
 * not session expiry.
 */

const BASE = "/rest/saiku/demo/gate";

export interface GateStatus {
  enabled: boolean;
  verified: boolean;
  provider: string | null;
}

/** Whether the gate is on and whether this visitor already cleared it. */
export async function gateStatus(): Promise<GateStatus> {
  try {
    const res = await fetch(`${BASE}/status`, {
      credentials: "include",
      headers: { Accept: "application/json" },
    });
    if (!res.ok) return { enabled: false, verified: false, provider: null };
    return (await res.json()) as GateStatus;
  } catch {
    return { enabled: false, verified: false, provider: null };
  }
}

/** Ask the server to email a one-time code to {@code email}. */
export async function requestCode(email: string): Promise<void> {
  const res = await postJson(`${BASE}/request`, { email });
  if (!res.ok) {
    throw new Error(await errorMessage(res, "Could not send the code. Please try again."));
  }
}

/** Verify the {@code code} for {@code email}; on success the server sets the cookie. */
export async function verifyCode(email: string, code: string): Promise<void> {
  const res = await postJson(`${BASE}/verify`, { email, code });
  if (!res.ok) {
    throw new Error(await errorMessage(res, "That code is invalid or has expired."));
  }
}

function postJson(url: string, body: unknown): Promise<Response> {
  return fetch(url, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  });
}

async function errorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const data = (await res.json()) as { error?: unknown };
    if (data && typeof data.error === "string" && data.error) return data.error;
  } catch {
    // non-JSON body — fall through to the fallback
  }
  return fallback;
}
