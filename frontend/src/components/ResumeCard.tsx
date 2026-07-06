import { Link } from "react-router-dom";
import type { HistoryEntry } from "../types";
import { posterGradient } from "../lib/colors";

/** 16:9 card for "continue watching" rails: play overlay, progress bar, `SxEy · pct%` label. */
export function ResumeCard({ entry }: { entry: HistoryEntry }) {
  const pct = entry.durationSeconds
    ? Math.round((entry.positionSeconds / entry.durationSeconds) * 100)
    : 0;
  const episodeLabel =
    entry.season != null && entry.episode != null
      ? `S${entry.season}E${entry.episode}${pct ? ` · ${pct}%` : ""}`
      : pct
        ? `${pct}%`
        : null;
  return (
    <Link
      to={`/media/${entry.providerId}?id=${encodeURIComponent(entry.mediaId)}`}
      className="resume-card"
      title={entry.episodeName ?? entry.title}
    >
      <div className="resume-card-poster">
        {entry.posterUrl ? (
          <img src={entry.posterUrl} alt={entry.title} loading="lazy" />
        ) : (
          <div className="media-card-placeholder" style={{ background: posterGradient(entry.title) }}>
            <span className="media-card-placeholder-title">{entry.title}</span>
          </div>
        )}
        <div className="resume-card-play" aria-hidden="true">▶</div>
        <div className="resume-bar">
          <div className="resume-fill" style={{ width: `${pct}%` }} />
        </div>
      </div>
      <div className="media-card-title">{entry.title}</div>
      {episodeLabel && <div className="media-card-year">{episodeLabel}</div>}
    </Link>
  );
}
