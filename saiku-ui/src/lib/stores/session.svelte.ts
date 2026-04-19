import { getCurrentSession, login as apiLogin, logout as apiLogout, type SaikuSession } from "$lib/api/session";

class SessionStore {
  current = $state<SaikuSession | null>(null);
  loading = $state<boolean>(true);

  async bootstrap(): Promise<void> {
    this.loading = true;
    try {
      this.current = await getCurrentSession();
    } finally {
      this.loading = false;
    }
  }

  async login(username: string, password: string): Promise<void> {
    const s = await apiLogin(username, password);
    this.current = s;
  }

  async logout(): Promise<void> {
    await apiLogout();
    this.current = null;
  }

  get isAdmin(): boolean {
    return this.current?.isadmin === true;
  }
}

export const session = new SessionStore();
