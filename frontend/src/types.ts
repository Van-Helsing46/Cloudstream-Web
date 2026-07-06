// Types aligned with the backend's domain model (com.cloudstreamweb.domain).

export type MediaType = "MOVIE" | "TV_SERIES" | "ANIME" | "LIVE" | "OTHER";

export interface SearchItem {
  id: string;
  providerId: string;
  title: string;
  type: MediaType;
  posterUrl?: string | null;
  year?: number | null;
}

export interface SearchResponse {
  query: string;
  results: SearchItem[];
  /** Providers that failed in the aggregated search: providerId → message. */
  errors: Record<string, string>;
}

export interface HomeSection {
  title: string;
  items: SearchItem[];
  isHorizontal: boolean;
}

export interface HomeResponse {
  providerId: string;
  page: number;
  sections: HomeSection[];
}

export interface Episode {
  id: string;
  name?: string | null;
  season?: number | null;
  episode?: number | null;
  posterUrl?: string | null;
}

export interface MediaDetail {
  id: string;
  providerId: string;
  title: string;
  type: MediaType;
  plot?: string | null;
  posterUrl?: string | null;
  year?: number | null;
  episodes: Episode[];
}

export interface StreamLink {
  url: string;
  quality?: string | null;
  isM3u8: boolean;
  headers: Record<string, string>;
}

export interface ProviderInfo {
  id: string;
  name: string;
  language?: string | null;
  supportedTypes: MediaType[];
  hasMainPage: boolean;
}

// ---- Extension management (aligned with extensions/RepositoryModels.kt) ----

export interface RepositoryRef {
  url: string;
  name: string;
  pluginLists: string[];
}

export interface AvailablePlugin {
  internalName: string;
  name: string;
  version: number;
  status: number;
  description?: string | null;
  language?: string | null;
  tvTypes: string[];
  iconUrl?: string | null;
  repositoryUrl?: string | null;
  installedVersion?: number | null;
  runtimeSupported: boolean;
}

export interface InstalledExtension {
  internalName: string;
  name: string;
  version: number;
  cs3Url: string;
  repositoryUrl?: string | null;
  language?: string | null;
  installedAt: string;
}

export interface InstallResult {
  extension: InstalledExtension;
  runtimeActive: boolean;
  message?: string | null;
}

// ---- User library (aligned with library/LibraryModels.kt) ----

export interface LibraryItem {
  providerId: string;
  mediaId: string;
  title: string;
  type: MediaType;
  posterUrl?: string | null;
  year?: number | null;
  addedAt: string;
}

export interface HistoryEntry {
  providerId: string;
  mediaId: string;
  episodeId: string;
  title: string;
  episodeName?: string | null;
  season?: number | null;
  episode?: number | null;
  posterUrl?: string | null;
  positionSeconds: number;
  durationSeconds?: number | null;
  updatedAt: string;
}

export interface ProgressRequest {
  providerId: string;
  mediaId: string;
  episodeId: string;
  title: string;
  episodeName?: string | null;
  season?: number | null;
  episode?: number | null;
  posterUrl?: string | null;
  positionSeconds: number;
  durationSeconds?: number | null;
}

// ---- Profiles (multi-user) ----

export interface Profile {
  id: string;
  name: string;
  color: string;
  createdAt: string;
}

/** Partial update: omitted fields keep their current value. */
export interface UpdateProfileRequest {
  name?: string;
  color?: string;
}
