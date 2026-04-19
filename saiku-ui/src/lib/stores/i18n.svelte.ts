import { browser } from "$app/environment";
import enBundle from "$lib/i18n/en.json";
import deBundle from "$lib/i18n/de.json";

export type Locale = "en" | "de";

const BUNDLES: Record<Locale, Record<string, string>> = {
  en: enBundle as Record<string, string>,
  de: deBundle as Record<string, string>,
};

export const LOCALES: { id: Locale; label: string }[] = [
  { id: "en", label: "English" },
  { id: "de", label: "Deutsch" },
];

const STORAGE_KEY = "saiku.locale";

function readStored(): Locale {
  if (!browser) return "en";
  const raw = localStorage.getItem(STORAGE_KEY);
  return raw === "en" || raw === "de" ? raw : detectFromNavigator();
}

function detectFromNavigator(): Locale {
  if (!browser) return "en";
  const nav = navigator.language?.slice(0, 2).toLowerCase();
  return nav === "de" ? "de" : "en";
}

class I18nStore {
  locale = $state<Locale>(readStored());

  constructor() {
    if (browser) {
      $effect.root(() => {
        $effect(() => {
          document.documentElement.lang = this.locale;
          localStorage.setItem(STORAGE_KEY, this.locale);
        });
      });
    }
  }

  set(l: Locale): void {
    this.locale = l;
  }

  t(key: string, fallback?: string): string {
    const bundle = BUNDLES[this.locale];
    return bundle[key] ?? fallback ?? key;
  }
}

export const i18n = new I18nStore();

/** Convenience helper — reactive in Svelte thanks to the runes store. */
export function t(key: string, fallback?: string): string {
  return i18n.t(key, fallback);
}
