import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { useT } from "../i18n";
import { posterGradient } from "../lib/colors";
import type { SearchItem } from "../types";

const MIN_QUERY_LENGTH = 2;
const DEBOUNCE_MS = 400;
const MAX_RESULTS = 6;

/**
 * Always-present search box in the topbar: debounced live results in a dropdown,
 * Enter or "all results" → the full results page.
 */
export function SearchBox() {
  const t = useT();
  const navigate = useNavigate();
  const location = useLocation();
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  const [open, setOpen] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const blurTimeout = useRef<number | undefined>(undefined);

  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(query.trim()), DEBOUNCE_MS);
    return () => window.clearTimeout(id);
  }, [query]);

  // Close the dropdown on every navigation, regardless of how it was triggered.
  useEffect(() => {
    setOpen(false);
  }, [location.pathname, location.search]);

  useEffect(() => () => window.clearTimeout(blurTimeout.current), []);

  const enabled = debounced.length >= MIN_QUERY_LENGTH;
  const live = useQuery({
    queryKey: ["search-live", debounced],
    queryFn: () => api.search(debounced),
    enabled,
    staleTime: 30_000,
  });

  const providers = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const providerName = (id: string) => providers.data?.find((p) => p.id === id)?.name ?? id;

  function goToResults(q: string) {
    window.clearTimeout(blurTimeout.current);
    inputRef.current?.blur();
    navigate(`/search?q=${encodeURIComponent(q)}`);
  }

  function goToItem(item: SearchItem) {
    window.clearTimeout(blurTimeout.current);
    inputRef.current?.blur();
    navigate(`/media/${item.providerId}?id=${encodeURIComponent(item.id)}`);
  }

  const results = (live.data?.results ?? []).slice(0, MAX_RESULTS);
  const showDropdown = open && enabled;

  return (
    <div className="searchbox">
      <svg className="searchbox-icon" width="15" height="15" viewBox="0 0 20 20" aria-hidden="true">
        <circle cx="9" cy="9" r="6.2" fill="none" stroke="currentColor" strokeWidth="2" />
        <line x1="13.8" y1="13.8" x2="18" y2="18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      </svg>
      <input
        ref={inputRef}
        className="searchbox-input"
        value={query}
        onChange={(e) => {
          setQuery(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => {
          blurTimeout.current = window.setTimeout(() => setOpen(false), 180);
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter" && query.trim()) goToResults(query.trim());
          if (e.key === "Escape") {
            setOpen(false);
            inputRef.current?.blur();
          }
        }}
        placeholder={t("search.placeholder")}
      />
      {showDropdown && (
        <div className="searchbox-dropdown">
          {live.isFetching && <div className="searchbox-status muted">{t("search.searching")}</div>}
          {!live.isFetching &&
            results.map((item, i) => (
              <button
                key={`${item.providerId}:${item.id}-${i}`}
                type="button"
                className="searchbox-result"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => goToItem(item)}
              >
                <div
                  className="searchbox-thumb"
                  style={
                    item.posterUrl
                      ? { backgroundImage: `url(${item.posterUrl})` }
                      : { background: posterGradient(item.title) }
                  }
                />
                <div className="searchbox-result-info">
                  <span className="searchbox-result-title">{item.title}</span>
                  <span className="searchbox-result-meta muted">
                    {[item.year, item.type, providerName(item.providerId)].filter(Boolean).join(" · ")}
                  </span>
                </div>
              </button>
            ))}
          {!live.isFetching && results.length === 0 && (
            <div className="searchbox-status muted">{t("search.noTitlesFound")}</div>
          )}
          <button
            type="button"
            className="searchbox-all"
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => query.trim() && goToResults(query.trim())}
            disabled={!query.trim()}
          >
            <span>{t("search.allResults", { query: query.trim() })}</span>
            <span className="searchbox-enter-hint">↵</span>
          </button>
        </div>
      )}
    </div>
  );
}
