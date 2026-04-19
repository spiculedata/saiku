import { browser } from "$app/environment";

type Theme = "light" | "dark" | "system";

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

class ThemeStore {
  theme = $state<Theme>(readStored());

  constructor() {
    if (browser) {
      $effect.root(() => {
        $effect(() => {
          apply(this.theme);
          if (this.theme === "system") localStorage.removeItem(STORAGE_KEY);
          else localStorage.setItem(STORAGE_KEY, this.theme);
        });
      });
    }
  }

  toggle(): void {
    this.theme = this.theme === "dark" ? "light" : "dark";
  }

  set(t: Theme): void {
    this.theme = t;
  }
}

export const theme = new ThemeStore();
