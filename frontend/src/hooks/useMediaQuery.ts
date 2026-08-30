import { useEffect, useState } from "react";

/** Tracks a CSS media query in JS, e.g. `useMediaQuery("(max-width: 768px)")`. */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches);

  useEffect(() => {
    const mql = window.matchMedia(query);
    const onChange = () => setMatches(mql.matches);
    onChange();
    mql.addEventListener("change", onChange);
    return () => mql.removeEventListener("change", onChange);
  }, [query]);

  return matches;
}

/** Shared breakpoint for the mobile layout (bottom nav, stacked detail header, ...). */
export const MOBILE_QUERY = "(max-width: 768px)";
export const useIsMobile = () => useMediaQuery(MOBILE_QUERY);
