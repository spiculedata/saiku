import { browser } from "$app/environment";

type Theme = "light" | "dark" | "system";
type EffectiveTheme = "light" | "dark";

const STORAGE_KEY = "saiku.theme";

function readStored(): Theme {
  if (!browser) return "system";
  const raw = localStorage.getItem(STORAGE_KEY);
  return raw === "light" || raw === "dark" ? raw : "system";
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
}

export const theme = new ThemeStore();
