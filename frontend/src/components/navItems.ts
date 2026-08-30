import type { TranslationKey } from "../i18n";

/** Primary sections, shared between the desktop TopBar and the mobile BottomNav. */
export const NAV_ITEMS: { to: string; end: boolean; key: TranslationKey }[] = [
  { to: "/", end: true, key: "nav.home" },
  { to: "/library", end: false, key: "nav.library" },
  { to: "/extensions", end: false, key: "nav.extensions" },
];
