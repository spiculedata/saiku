import { browser } from "$app/environment";

/** Shape returned by GET /rest/saiku/info/capabilities. */
export interface PlatformCapabilities {
  ai: {
    enabled: boolean;
    basePath: string;
    endpoints: Record<string, string>;
  };
  mcp: {
    enabled: boolean;
    url?: string;
    transport?: string;
  };
  /** True when the launcher was started with -Dsaiku.demo=true. */
  demoMode: boolean;
}

class PlatformStore {
  fullscreen = $state<boolean>(false);
  /** Version info from /rest/saiku/info + /rest/saiku/admin/version. */
  version = $state<string | null>(null);
  newVersionAvailable = $state<boolean>(false);
  /** Capabilities probe — populated lazily by loadCapabilities. Null
   *  until the first successful fetch so callers can show a loading state. */
  capabilities = $state<PlatformCapabilities | null>(null);

  constructor() {
    if (browser) {
      document.addEventListener("fullscreenchange", () => {
        this.fullscreen = !!document.fullscreenElement;
      });
    }
  }

  async toggleFullscreen(): Promise<void> {
    if (!browser) return;
    if (!document.fullscreenElement) {
      await document.documentElement.requestFullscreen?.();
    } else {
      await document.exitFullscreen?.();
    }
  }

  async loadVersion(): Promise<void> {
    try {
      const res = await fetch("/rest/saiku/admin/version", { credentials: "include" });
      if (res.ok) this.version = (await res.text()).trim() || null;
    } catch {
      // no-op
    }
  }

  /**
   * Probe /rest/saiku/info/capabilities so callers (admin info panel,
   * conditional login-page demo banner) know whether AI/MCP are exposed
   * and what URLs to surface. Anonymous-accessible, no auth required.
   */
  async loadCapabilities(): Promise<void> {
    try {
      const res = await fetch("/rest/saiku/info/capabilities", {
        credentials: "include",
        headers: { Accept: "application/json" },
      });
      if (res.ok) {
        this.capabilities = (await res.json()) as PlatformCapabilities;
      }
    } catch {
      // Capability probe failures are non-fatal — the rest of the UI
      // continues without the connection-info panel.
    }
  }

  /** Fire-and-forget anonymous stats ping (legacy plugins/Statistics). */
  ping(): void {
    if (!browser) return;
    const img = new Image();
    img.src = `/rest/saiku/info?ts=${Date.now()}`;
  }
}

export const platform = new PlatformStore();
