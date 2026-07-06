import { Link } from "react-router-dom";
import type { SearchItem } from "../types";
import { posterGradient } from "../lib/colors";

/** Clickable poster card → detail page. Horizontal (landscape) or vertical (portrait). */
export function MediaCard({ item, horizontal = false }: { item: SearchItem; horizontal?: boolean }) {
  const width = horizontal ? 260 : 150;
  const aspect = horizontal ? "16 / 9" : "2 / 3";
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
      </div>
      <div className="media-card-title">{item.title}</div>
      {item.year != null && <div className="media-card-year">{item.year}</div>}
    </Link>
  );
}
