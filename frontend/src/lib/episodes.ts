import type { Episode } from "../types";

/** The backend passes episodes in provider order; sort by season then episode for play/next-episode logic. */
export function sortEpisodes(episodes: Episode[]): Episode[] {
  return [...episodes].sort((a, b) => {
    const seasonDiff = (a.season ?? 0) - (b.season ?? 0);
    if (seasonDiff !== 0) return seasonDiff;
    return (a.episode ?? 0) - (b.episode ?? 0);
  });
}
