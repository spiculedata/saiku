import type { OidcInfo, OidcCredential } from "./types";
declare const Office: any;

function randString(n: number): string {
  const a = new Uint8Array(n);
  crypto.getRandomValues(a);
  return Array.from(a, b => ("0" + (b & 0xff).toString(16)).slice(-2)).join("");
}

async function pkce(): Promise<{ verifier: string; challenge: string }> {
  const verifier = randString(32);
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  const challenge = btoa(String.fromCharCode(...new Uint8Array(digest)))
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return { verifier, challenge };
}

/**
 * Run the OAuth PKCE authorization-code flow in an Office dialog.
 *
 * NOTE: Verify the WorkOS authorize/token URLs against the issuer's discovery doc
 * (`<issuer>/.well-known/openid-configuration`) during the gate. The `authorizeUrl`
 * from `/auth-capabilities` overrides the guessed path. Register the redirectUri
 * (`<origin>/addin/auth-callback.html`) in the WorkOS app config before going live.
 */
export async function signIn(oidc: OidcInfo): Promise<OidcCredential> {
  const { verifier, challenge } = await pkce();
  const state = randString(8);
  const redirectUri = `${location.origin}/addin/auth-callback.html`;

  // Use the authorizeUrl from capabilities if provided; otherwise derive from issuer.
  const authorize = oidc.authorizeUrl ?? `${oidc.issuer.replace(/\/$/, "")}/oauth2/authorize`;
  const url = `${authorize}?response_type=code&client_id=${encodeURIComponent(oidc.clientId)}`
    + `&redirect_uri=${encodeURIComponent(redirectUri)}&scope=${encodeURIComponent("openid profile email")}`
    + `&code_challenge=${challenge}&code_challenge_method=S256&state=${state}`;

  const code = await new Promise<string>((resolve, reject) => {
    Office.context.ui.displayDialogAsync(url, { height: 60, width: 30 }, (res: any) => {
      if (res.status !== "succeeded") { reject(new Error("Could not open sign-in dialog")); return; }
      const dialog = res.value;
      dialog.addEventHandler(Office.EventType.DialogMessageReceived, (arg: any) => {
        dialog.close();
        const m = JSON.parse(arg.message);
        if (m.error) reject(new Error(m.error));
        else if (m.state !== state) reject(new Error("State mismatch"));
        else resolve(m.code);
      });
      dialog.addEventHandler(Office.EventType.DialogEventReceived, () => reject(new Error("Sign-in cancelled")));
    });
  });

  // Token endpoint: derived from issuer (guessed path — verify against discovery doc).
  const tokenUrl = `${oidc.issuer.replace(/\/$/, "")}/oauth2/token`;
  const body = new URLSearchParams({
    grant_type: "authorization_code", code, client_id: oidc.clientId,
    redirect_uri: redirectUri, code_verifier: verifier
  });
  const tr = await fetch(tokenUrl, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body });
  if (!tr.ok) throw new Error(`Token exchange failed (HTTP ${tr.status})`);
  const t = await tr.json();
  return {
    kind: "oidc",
    accessToken: t.access_token,
    refreshToken: t.refresh_token,
    expiresAt: t.expires_in ? Date.now() + t.expires_in * 1000 : undefined
  };
}
