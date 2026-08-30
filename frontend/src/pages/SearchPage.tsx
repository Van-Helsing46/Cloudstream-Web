import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { MediaCard } from "../components/MediaCard";
import { useT } from "../i18n";
import { useIsMobile } from "../hooks/useMediaQuery";

const DEBOUNCE_MS = 400;

/**
 * Search results page. On desktop the query text lives only in the topbar's SearchBox and
 * this page is a pure view over the `?q=` URL param. On mobile there is no topbar search box
 * (see BottomNav), so this page also owns a debounced input that drives the same `?q=` param.
 */
export function SearchPage() {
  const t = useT();
  const navigate = useNavigate();
  const isMobile = useIsMobile();
  const [params] = useSearchParams();
  const submitted = params.get("q") ?? "";
  const [providerFilter, setProviderFilter] = useState<string | null>(null);
  const [mobileQuery, setMobileQuery] = useState(submitted);

  // Keep the mobile input in sync when navigation changes ?q= from elsewhere (e.g. back/forward).
  useEffect(() => setMobileQuery(submitted), [submitted]);

  useEffect(() => {
    if (!isMobile) return;
    const trimmed = mobileQuery.trim();
    if (trimmed === submitted) return;
    const id = window.setTimeout(() => {
      navigate(trimmed ? `/search?q=${encodeURIComponent(trimmed)}` : "/search", { replace: true });
    }, DEBOUNCE_MS);
    return () => window.clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mobileQuery, isMobile]);

  // A new query invalidates any provider filter picked for the previous one.
  useEffect(() => {
    setProviderFilter(null);
  }, [submitted]);

  const providers = useQuery({ queryKey: ["providers"], queryFn: api.providers });
  const providerName = (id: string) => providers.data?.find((p) => p.id === id)?.name ?? id;

  const search = useQuery({
    queryKey: ["search", submitted],
    queryFn: () => api.search(submitted),
    enabled: submitted.length > 0,
  });

  const errors = Object.entries(search.data?.errors ?? {});
  const allResults = search.data?.results ?? [];
  const providerIds = useMemo(
    () => [...new Set(allResults.map((r) => r.providerId))],
    [allResults],
  );
  const results = providerFilter ? allResults.filter((r) => r.providerId === providerFilter) : allResults;

  const mobileInput = isMobile && (
    <div className="search-page-input">
      <svg width="15" height="15" viewBox="0 0 20 20" aria-hidden="true">
        <circle cx="9" cy="9" r="6.2" fill="none" stroke="currentColor" strokeWidth="2" />
        <line x1="13.8" y1="13.8" x2="18" y2="18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      </svg>
      <input
        autoFocus={!submitted}
        value={mobileQuery}
        onChange={(e) => setMobileQuery(e.target.value)}
        placeholder={t("search.placeholder")}
      />
    </div>
  );

  if (!submitted) {
    return (
      <>
        {mobileInput}
        <p className="muted">{t("search.empty")}</p>
      </>
    );
  }

  return (
    <>
      {mobileInput}
      <div className="page-head">
        <h1>{t("search.resultsFor", { query: submitted })}</h1>
        {search.data && (
          <span className="muted">
            {t(results.length === 1 ? "search.resultCountOne" : "search.resultCountOther", {
              count: results.length,
            })}
          </span>
        )}
      </div>

      {providerIds.length > 1 && (
        <div className="chip-row">
          <button
            className={providerFilter === null ? "chip chip-active" : "chip"}
            onClick={() => setProviderFilter(null)}
          >
            {t("search.allProviders")}
          </button>
          {providerIds.map((id) => (
            <button
              key={id}
              className={providerFilter === id ? "chip chip-active" : "chip"}
              onClick={() => setProviderFilter(id)}
            >
              {providerName(id)}
            </button>
          ))}
        </div>
      )}

      {search.isLoading && <p className="muted">{t("search.searching")}</p>}
      {search.isError && <p className="error">{String(search.error)}</p>}

      {errors.length > 0 && (
        <div className="warn-banner">
          {errors.map(([id, msg]) => (
            <div key={id}>
              <strong>{id}</strong>: {msg}
            </div>
          ))}
        </div>
      )}

      {search.data && results.length === 0 && errors.length === 0 && (
        <p className="muted">{t("search.noResults", { query: submitted })}</p>
      )}

      <div className="card-grid" style={{ marginTop: 16 }}>
        {results.map((item, i) => (
          <MediaCard
            key={`${item.providerId}:${item.id}-${i}`}
            item={item}
            actions
            providerLabel={providerIds.length > 1 ? providerName(item.providerId) : undefined}
          />
        ))}
      </div>
    </>
  );
}
