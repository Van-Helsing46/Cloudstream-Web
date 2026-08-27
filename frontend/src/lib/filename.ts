/** Strips characters that are unsafe in a filename on Windows/macOS/Linux, and caps length. */
export function sanitizeFilenameBase(raw: string): string {
  const cleaned = raw.replace(/[\\/:*?"<>|]/g, "_").replace(/\s+/g, " ").trim();
  return cleaned.slice(0, 150) || "stream";
}

/** Builds the "{title} - S02E05 - {episode name}" base used for downloaded episode files. */
export function episodeFilenameBase(
  title: string,
  episode: { season?: number | null; episode?: number | null; name?: string | null },
): string {
  const parts = [title];
  if (episode.season != null && episode.episode != null) {
    const season = String(episode.season).padStart(2, "0");
    const number = String(episode.episode).padStart(2, "0");
    parts.push(`S${season}E${number}`);
  }
  if (episode.name) parts.push(episode.name);
  return sanitizeFilenameBase(parts.join(" - "));
}
