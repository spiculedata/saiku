import { apiJson } from "./http";
export async function login(username: string, password: string): Promise<void> {
  const body = `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`;
  const res = await fetch("/rest/saiku/session", {
    method: "POST", credentials: "include",
    headers: { "Content-Type": "application/x-www-form-urlencoded" }, body
  });
  if (!res.ok) throw new Error("Login failed");
  await fetch("/rest/saiku/session", { credentials: "include" });
}
export interface Session { username: string; roles: string[]; isadmin?: boolean; }
export const session = () => apiJson<Session>("/rest/saiku/session");
export const logout = () => fetch("/rest/saiku/session", { method: "DELETE", credentials: "include" });
