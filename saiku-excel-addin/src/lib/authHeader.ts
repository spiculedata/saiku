import type { Credential } from "./types";

export function authHeader(cred: Credential | null | undefined): string | null {
  if (!cred) return null;
  if (cred.kind === "basic") {
    if (!cred.username) return null;
    return "Basic " + btoa(`${cred.username}:${cred.password ?? ""}`);
  }
  if (cred.kind === "oidc") {
    return cred.accessToken ? "Bearer " + cred.accessToken : null;
  }
  return null;
}
