import { browser } from "$app/environment";

type Theme = "light" | "dark" | "system";
type EffectiveTheme = "light" | "dark";

const STORAGE_KEY = "saiku.theme";
// #1091: colour-blind-safe / high-contrast chart mode. Persisted as its own
// boolean rather than a fourth Theme value so it composes orthogonally with
// light/dark/system — a low-vision user can still pick a dark background.
const CB_STORAGE_KEY = "saiku.colorBlindSafe";

function readStored(): Theme {
  if (!browser) return "system";
  const raw = localStorage.getItem(STORAGE_KEY);
  return raw === "light" || raw === "dark" ? raw : "system";
}

function readStoredColorBlindSafe(): boolean {
  if (!browser) return false;
  return localStorage.getItem(CB_STORAGE_KEY) === "1";
}

function apply(theme: Theme): void {
  if (!browser) return;
  const root = document.documentElement;
  if (theme === "system") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", theme);
}

function systemPrefersDark(): boolean {
  if (!browser) return false;
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}

class ThemeStore {
  theme = $state<Theme>(readStored());
  /** What's actually rendered: "light" or "dark". Resolves "system" via media query. */
  effective = $state<EffectiveTheme>("light");
  /** #1091: when ON, charts use a colour-blind-safe palette + stronger contrast.
   *  A global app preference (persisted in localStorage like `theme`), read by
   *  resolveThemeTokens() so both the workspace and dashboard inherit it. */
  colorBlindSafe = $state<boolean>(readStoredColorBlindSafe());

  constructor() {
    if (browser) {
      this.effective = this.theme === "system"
        ? (systemPrefersDark() ? "dark" : "light")
        : this.theme;

      const mql = window.matchMedia("(prefers-color-scheme: dark)");
      const onSystemChange = () => {
        if (this.theme === "system") {
          this.effective = mql.matches ? "dark" : "light";
        }
      };
      if (mql.addEventListener) mql.addEventListener("change", onSystemChange);
      else mql.addListener(onSystemChange);

      $effect.root(() => {
        $effect(() => {
          apply(this.theme);
          if (this.theme === "system") localStorage.removeItem(STORAGE_KEY);
          else localStorage.setItem(STORAGE_KEY, this.theme);
          this.effective = this.theme === "system"
            ? (mql.matches ? "dark" : "light")
            : this.theme;
        });
        // #1091: persist the colour-blind-safe preference separately.
        $effect(() => {
          if (this.colorBlindSafe) localStorage.setItem(CB_STORAGE_KEY, "1");
          else localStorage.removeItem(CB_STORAGE_KEY);
        });
      });
    }
  }

  toggle(): void {
    this.theme = this.theme === "light"
      ? "dark"
      : this.theme === "dark"
        ? "system"
        : "light";
  }

  set(t: Theme): void {
    this.theme = t;
  }

  /** #1091: flip the colour-blind-safe chart mode on/off. */
  toggleColorBlindSafe(): void {
    this.colorBlindSafe = !this.colorBlindSafe;
  }

  setColorBlindSafe(on: boolean): void {
    this.colorBlindSafe = on;
  }
}

export const theme = new ThemeStore();
