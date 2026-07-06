import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { en } from "./en";
import { it } from "./it";

export type Language = "en" | "it";

const LANG_KEY = "cs_lang";

/** Flattens a nested dictionary into dot-path keys, e.g. `{ nav: { home: "Home" } }` → `{ "nav.home": "Home" }`. */
function flatten(obj: object, prefix = ""): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === "string") out[path] = value;
    else Object.assign(out, flatten(value as object, path));
  }
  return out;
}

const flatDictionaries: Record<Language, Record<string, string>> = {
  en: flatten(en),
  it: flatten(it),
};

/** All valid dot-path translation keys, derived from the English dictionary. */
type Flatten<T, Prefix extends string = ""> = T extends string
  ? never
  : {
      [K in keyof T & string]: T[K] extends string
        ? `${Prefix}${K}`
        : Flatten<T[K], `${Prefix}${K}.`>;
    }[keyof T & string];

export type TranslationKey = Flatten<typeof en>;

/** Reads the persisted language, falling back to the browser locale, then English. */
export function getLanguage(): Language {
  const stored = typeof localStorage !== "undefined" ? localStorage.getItem(LANG_KEY) : null;
  if (stored === "en" || stored === "it") return stored;
  const nav = typeof navigator !== "undefined" ? navigator.language : "en";
  return nav.toLowerCase().startsWith("it") ? "it" : "en";
}

/** Persists the language choice for the next visit. */
export function setLanguage(lang: Language) {
  localStorage.setItem(LANG_KEY, lang);
}

interface LanguageContextValue {
  language: Language;
  /** Switches the language for the current session and persists the choice. */
  changeLanguage: (lang: Language) => void;
  t: (key: TranslationKey, params?: Record<string, string | number>) => string;
}

const LanguageContext = createContext<LanguageContextValue | null>(null);

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [language, setLanguageState] = useState<Language>(getLanguage);

  const changeLanguage = useCallback((lang: Language) => {
    setLanguage(lang);
    setLanguageState(lang);
  }, []);

  const t = useCallback(
    (key: TranslationKey, params?: Record<string, string | number>): string => {
      const dict = flatDictionaries[language];
      let value = dict[key] ?? flatDictionaries.en[key] ?? key;
      if (params) {
        for (const [name, val] of Object.entries(params)) {
          value = value.replaceAll(`{${name}}`, String(val));
        }
      }
      return value;
    },
    [language],
  );

  const value = useMemo(() => ({ language, changeLanguage, t }), [language, changeLanguage, t]);

  return <LanguageContext.Provider value={value}>{children}</LanguageContext.Provider>;
}

function useLanguageContext(): LanguageContextValue {
  const ctx = useContext(LanguageContext);
  if (!ctx) throw new Error("useT/useLanguage outside LanguageProvider");
  return ctx;
}

/** Translation function: `t('nav.home')` or `t('search.noResults', { query })`. */
export function useT() {
  return useLanguageContext().t;
}

/** Current language + setter, for the language switcher. */
export function useLanguage() {
  const { language, changeLanguage } = useLanguageContext();
  return { language, setLanguage: changeLanguage };
}
