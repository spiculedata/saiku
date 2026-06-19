import type { Capabilities, AuthStrategyKind } from "./types";

export async function fetchCapabilities(baseUrl: string): Promise<Capabilities> {
  const res = await fetch(`${baseUrl}/rest/saiku/info/auth-capabilities`);
  if (!res.ok) throw new Error(`Capabilities probe failed (HTTP ${res.status})`);
  return (await res.json()) as Capabilities;
}

export function chooseStrategies(caps: Capabilities): AuthStrategyKind[] {
  const out: AuthStrategyKind[] = [];
  if (caps.oidc && caps.oidc.issuer && caps.oidc.clientId) out.push("oidc");
  if (caps.basic) out.push("basic");
  return out;
}
