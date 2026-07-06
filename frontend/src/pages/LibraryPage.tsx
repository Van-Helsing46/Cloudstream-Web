import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { MediaCard } from "../components/MediaCard";
import { ResumeCard } from "../components/ResumeCard";
import { useT } from "../i18n";

/** Library: "continue watching" + watchlist. */
export function LibraryPage() {
  const t = useT();
  const watchlist = useQuery({ queryKey: ["library", "watchlist"], queryFn: api.library.watchlist });
  const continueW = useQuery({
    queryKey: ["library", "continue"],
    queryFn: api.library.continueWatching,
  });

  return (
    <>
      <h1>{t("library.title")}</h1>

      <div className="page-sections">
        <section>
          <h2 className="rail-title">{t("library.continueWatching")}</h2>
          {continueW.data?.length === 0 && (
            <p className="muted">{t("library.nothingToResume")}</p>
          )}
          <div className="rail-track">
            {continueW.data?.map((h) => <ResumeCard key={`${h.providerId}:${h.episodeId}`} entry={h} />)}
          </div>
        </section>

        <section>
          <h2 className="rail-title">{t("library.watchlist")}</h2>
          {watchlist.data?.length === 0 && (
            <p className="muted">{t("library.emptyWatchlist")}</p>
          )}
          <div className="card-grid">
            {watchlist.data?.map((w) => (
              <MediaCard
                key={`${w.providerId}:${w.mediaId}`}
                item={{
                  id: w.mediaId,
                  providerId: w.providerId,
                  title: w.title,
                  type: w.type,
                  posterUrl: w.posterUrl,
                  year: w.year,
                }}
              />
            ))}
          </div>
        </section>
      </div>
    </>
  );
}
