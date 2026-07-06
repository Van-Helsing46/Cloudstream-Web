import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { MediaCard } from "../components/MediaCard";
import { useT } from "../i18n";

/**
 * Search results page. The query text lives only in the topbar's SearchBox;
 * this page is a pure view over the `?q=` URL param, with client-side provider chips.
 */
export function SearchPage() {
  const t = useT();
  const [params] = useSearchParams();
  const submitted = params.get("q") ?? "";
  const [providerFilter, setProviderFilter] = useState<string | null>(null);

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

  if (!submitted) {
    return <p className="muted">{t("search.empty")}</p>;
  }

  return (
    <>
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
          <MediaCard key={`${item.providerId}:${item.id}-${i}`} item={item} />
        ))}
      </div>
    </>
  );
}
