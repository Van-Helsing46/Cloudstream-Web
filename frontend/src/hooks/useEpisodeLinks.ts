import { useCallback, useRef, useState } from "react";
import { api } from "../api/client";
import type { Episode, StreamLink } from "../types";

/**
 * On-demand, per-episode cache of resolved stream links — the same `api.links` call the
 * player already makes, but reusable by the copy-link/download row actions without
 * re-resolving (and without triggering playback) every time the user opens the row.
 */
export function useEpisodeLinks(providerId: string) {
  const cache = useRef(new Map<string, StreamLink[]>());
  const [loadingId, setLoadingId] = useState<string | null>(null);

  const resolve = useCallback(
    async (episode: Episode): Promise<StreamLink[]> => {
      const cached = cache.current.get(episode.id);
      if (cached) return cached;
      setLoadingId(episode.id);
      try {
        const links = await api.links(providerId, episode.id);
        cache.current.set(episode.id, links);
        return links;
      } finally {
        setLoadingId((current) => (current === episode.id ? null : current));
      }
    },
    [providerId],
  );

  return { resolve, loadingId };
}
