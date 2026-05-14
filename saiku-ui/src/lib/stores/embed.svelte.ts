/**
 * Embed mode: when `?embed=1` is in the URL, the UI renders chromelessly so
 * the page can sit inside an iframe in a wiki/Confluence/Notion page.
 *
 * Embed still needs a session — we're not building anonymous access here.
 * The URL state (`?q=...`) keeps working independently; embed piggybacks on it.
 */
class EmbedStore {
  active = $state(false);

  bootstrap(): void {
    if (typeof window === "undefined") return;
    try {
      const params = new URLSearchParams(window.location.search);
      this.active = params.get("embed") === "1";
      if (this.active) {
        document.body.classList.add("saiku-embed");
      } else {
        document.body.classList.remove("saiku-embed");
      }
    } catch {
      this.active = false;
    }
  }
}

export const embed = new EmbedStore();
