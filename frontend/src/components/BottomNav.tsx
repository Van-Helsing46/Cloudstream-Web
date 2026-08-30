import { NavLink } from "react-router-dom";
import { useT, type TranslationKey } from "../i18n";
import { NAV_ITEMS } from "./navItems";

const ICONS: Record<string, JSX.Element> = {
  "/": (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M4 11.5 12 5l8 6.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M6 10.5V19a1 1 0 0 0 1 1h3v-5h4v5h3a1 1 0 0 0 1-1v-8.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
  "/library": (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="4" y="4" width="6" height="16" rx="1.4" stroke="currentColor" strokeWidth="2" />
      <rect x="14" y="4" width="6" height="10" rx="1.4" stroke="currentColor" strokeWidth="2" />
    </svg>
  ),
  "/extensions": (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M14 4h-4v2.5a1.5 1.5 0 0 1-3 0V4H5a1 1 0 0 0-1 1v4h2.5a1.5 1.5 0 0 1 0 3H4v4a1 1 0 0 0 1 1h4v-2.5a1.5 1.5 0 0 1 3 0V17h4a1 1 0 0 0 1-1v-4h-2.5a1.5 1.5 0 0 1 0-3H17V5a1 1 0 0 0-1-1Z"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
    </svg>
  ),
  "/search": (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="2" />
      <line x1="15.5" y1="15.5" x2="20" y2="20" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  ),
};

const SEARCH_ITEM: { to: string; end: boolean; key: TranslationKey } = { to: "/search", end: false, key: "nav.search" };

/** Fixed bottom navigation for mobile, replacing the desktop topbar nav + search box. */
export function BottomNav() {
  const t = useT();
  const items = [...NAV_ITEMS, SEARCH_ITEM];

  return (
    <nav className="bottom-nav" aria-label={t("nav.home")}>
      {items.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          end={item.end}
          className={({ isActive }) => (isActive ? "bottom-nav-link active" : "bottom-nav-link")}
        >
          {ICONS[item.to]}
          <span>{t(item.key)}</span>
        </NavLink>
      ))}
    </nav>
  );
}
