import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import type { LibraryItem } from "../types";

/** Shared watchlist state/actions, so any card or page can toggle without duplicating the mutation. */
export function useWatchlist() {
  const qc = useQueryClient();
  const watchlist = useQuery({
    queryKey: ["library", "watchlist"],
    queryFn: api.library.watchlist,
  });

  function isInWatchlist(providerId: string, mediaId: string): boolean {
    return watchlist.data?.some((w) => w.providerId === providerId && w.mediaId === mediaId) ?? false;
  }

  async function toggle(item: Omit<LibraryItem, "addedAt">) {
    if (isInWatchlist(item.providerId, item.mediaId)) {
      await api.library.removeWatchlist(item.providerId, item.mediaId);
    } else {
      await api.library.addWatchlist(item);
    }
    await qc.invalidateQueries({ queryKey: ["library"] });
  }

  return { watchlist, isInWatchlist, toggle };
}
