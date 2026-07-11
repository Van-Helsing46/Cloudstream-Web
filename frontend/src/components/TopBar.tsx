import { Link, NavLink } from "react-router-dom";
import { useT, type TranslationKey } from "../i18n";
import { useProfile } from "../pages/ProfileGate";
import { SearchBox } from "./SearchBox";
import { ProfileAvatar, profileAvatarStyle } from "./ProfileAvatar";

const NAV_ITEMS: { to: string; end: boolean; key: TranslationKey }[] = [
  { to: "/", end: true, key: "nav.home" },
  { to: "/library", end: false, key: "nav.library" },
  { to: "/extensions", end: false, key: "nav.extensions" },
];

/** Sticky topbar: brand, primary nav, the always-present search box, and the profile switcher. */
export function TopBar() {
  const t = useT();
  const { profile, switchProfile } = useProfile();

  return (
    <header className="topbar">
      <Link to="/" className="topbar-brand">
        <svg width="30" height="21" viewBox="0 0 34 24" aria-hidden="true">
          <circle cx="11" cy="14" r="8" fill="var(--accent-1)" />
          <circle cx="21" cy="11" r="9" fill="#3568e0" />
          <circle cx="27" cy="16" r="6" fill="var(--accent-2)" />
          <rect x="6" y="15" width="24" height="7" rx="3.5" fill="#3568e0" />
        </svg>
        <span className="topbar-wordmark">{t("common.appName")}</span>
      </Link>

      <nav className="topbar-nav">
        {NAV_ITEMS.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.end}
            className={({ isActive }) => (isActive ? "topbar-nav-link active" : "topbar-nav-link")}
          >
            {t(item.key)}
          </NavLink>
        ))}
      </nav>

      <SearchBox />

      <button
        className="topbar-avatar"
        style={profileAvatarStyle(profile)}
        onClick={switchProfile}
        title={t("nav.switchProfile")}
      >
        <ProfileAvatar profile={profile} />
      </button>
    </header>
  );
}
