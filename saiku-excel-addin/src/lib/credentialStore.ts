import type { Credential } from "./types";

declare const Office: any;

const KEY = (serverId: string) => `saiku.cred.${serverId}`;
const roaming = () => Office.context.roamingSettings;
const save = () => new Promise<void>((res, rej) =>
  roaming().saveAsync((r: { status: string }) =>
    r.status === "succeeded" ? res() : rej(new Error("save failed"))
  )
);

export async function saveCredential(serverId: string, cred: Credential): Promise<void> {
  roaming().set(KEY(serverId), cred);
  await save();
}

export async function loadCredential(serverId: string): Promise<Credential | null> {
  return (roaming().get(KEY(serverId)) as Credential) ?? null;
}

export async function clearCredential(serverId: string): Promise<void> {
  roaming().remove(KEY(serverId));
  await save();
}
