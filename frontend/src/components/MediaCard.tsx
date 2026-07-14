import { Link, useNavigate } from "react-router-dom";
import type { SearchItem } from "../types";
import { posterGradient } from "../lib/colors";
import { useWatchlist } from "../hooks/useWatchlist";
import { useT } from "../i18n";

/** Clickable poster card → detail page. Horizontal (landscape) or vertical (portrait). */
export function MediaCard({
  item,
  horizontal = false,
  actions = false,
}: {
  item: SearchItem;
  horizontal?: boolean;
  actions?: boolean;
}) {
  const t = useT();
  const navigate = useNavigate();
  const { isInWatchlist, toggle } = useWatchlist();
  const width = horizontal ? 260 : 150;
  const aspect = horizontal ? "16 / 9" : "2 / 3";
  const inWatchlist = actions && isInWatchlist(item.providerId, item.id);

  function handleWatchlist(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    void toggle({
      providerId: item.providerId,
      mediaId: item.id,
      title: item.title,
      type: item.type,
      posterUrl: item.posterUrl,
      year: item.year,
    });
  }

  function handlePlay(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    navigate(`/media/${item.providerId}?id=${encodeURIComponent(item.id)}&play=1`);
  }

  return (
    <Link
      to={`/media/${item.providerId}?id=${encodeURIComponent(item.id)}`}
      className="media-card"
      style={{ width }}
      title={item.title}
    >
      <div className="media-card-poster" style={{ aspectRatio: aspect }}>
        {item.posterUrl ? (
          <img src={item.posterUrl} alt={item.title} loading="lazy" />
        ) : (
          <div className="media-card-placeholder" style={{ background: posterGradient(item.title) }}>
            <span className="media-card-placeholder-title">{item.title}</span>
          </div>
        )}
        {actions && (
          <div className="media-card-actions">
            <button
              type="button"
              className={inWatchlist ? "media-card-action active" : "media-card-action"}
              onClick={handleWatchlist}
              title={inWatchlist ? t("detail.inWatchlist") : t("detail.addToWatchlist")}
              aria-label={inWatchlist ? t("detail.inWatchlist") : t("detail.addToWatchlist")}
            >
              {inWatchlist ? "✓" : "+"}
            </button>
            <button
              type="button"
              className="media-card-action"
              onClick={handlePlay}
              title={t("detail.play")}
              aria-label={t("detail.play")}
            >
              ▶
            </button>
          </div>
        )}
      </div>
      <div className="media-card-title">{item.title}</div>
      {item.year != null && <div className="media-card-year">{item.year}</div>}
    </Link>
  );
}
