import { browser } from "$app/environment";

class PlatformStore {
  fullscreen = $state<boolean>(false);
  /** Version info from /rest/saiku/info + /rest/saiku/admin/version. */
  version = $state<string | null>(null);
  newVersionAvailable = $state<boolean>(false);

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

  /** Fire-and-forget anonymous stats ping (legacy plugins/Statistics). */
  ping(): void {
    if (!browser) return;
    const img = new Image();
    img.src = `/rest/saiku/info?ts=${Date.now()}`;
  }
}

export const platform = new PlatformStore();
