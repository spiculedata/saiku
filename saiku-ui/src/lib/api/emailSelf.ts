/*
 * Email-self client — TypeScript bindings to /rest/saiku/api/email/*.
 *
 * "Email me this" lets a user send the current view/result to their own
 * configured address. The health probe tells the toolbar whether the backend
 * has mail configured at all; the send call posts the body verbatim.
 *
 * Backend: /saiku/api/email/health, /saiku/api/email/self.
 */

const EMAIL_HEALTH_URL = "/rest/saiku/api/email/health";
const EMAIL_SELF_URL = "/rest/saiku/api/email/self";

export interface MailHealth {
  configured: boolean;
}

/** GET /email/health. Returns {configured: false} on transport failure so the
 *  toolbar silently hides the "Email me this" button rather than showing an
 *  error. */
export async function fetchMailHealth(): Promise<MailHealth> {
  try {
    const res = await fetch(EMAIL_HEALTH_URL, {
      method: "GET",
      credentials: "include",
      headers: { Accept: "application/json" },
    });
    if (!res.ok) return { configured: false };
    const body = (await res.json()) as MailHealth;
    return { configured: !!body.configured };
  } catch {
    return { configured: false };
  }
}

/** POST /email/self. Sends the body verbatim and returns the raw Response —
 *  callers inspect status / parse the body themselves. */
export async function sendEmailSelf(body: unknown): Promise<Response> {
  return fetch(EMAIL_SELF_URL, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify(body),
  });
}
