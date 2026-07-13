import type {
  AvailablePlugin,
  HistoryEntry,
  HomeResponse,
  InstalledExtension,
  InstallResult,
  LibraryItem,
  MediaDetail,
  Profile,
  ProgressRequest,
  ProviderInfo,
  RepositoryRef,
  SearchResponse,
  StreamLink,
  UpdateProfileRequest,
} from "../types";

// In dev Vite proxies to the backend (see vite.config.ts), hence the relative base.
const BASE = "/api/v1";

/** Raised on 401s: the login gate intercepts it and shows the sign-in screen. */
export class UnauthorizedError extends Error {
  constructor() {
    super("Authentication required");
    this.name = "UnauthorizedError";
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, init);
  if (res.status === 401) throw new UnauthorizedError();
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const body = (await res.json()) as { error?: string };
      if (body.error) message = body.error;
    } catch {
      /* non-JSON body: keep the generic message */
    }
    throw new Error(message);
  }
  return res.json() as Promise<T>;
}

const getJson = <T>(path: string) => request<T>(path);

// ---- Current profile (multi-user) ----
// The selection lives in localStorage; the /library/* calls send it as X-Profile-Id.

const PROFILE_KEY = "cs_profile";

export function getProfileId(): string | null {
  return localStorage.getItem(PROFILE_KEY);
}

export function setProfileId(id: string | null) {
  if (id) localStorage.setItem(PROFILE_KEY, id);
  else localStorage.removeItem(PROFILE_KEY);
}

function profileHeaders(extra?: Record<string, string>): Record<string, string> {
  const id = getProfileId();
  return { ...(extra ?? {}), ...(id ? { "X-Profile-Id": id } : {}) };
}

// ---- Avatar cache-busting ----
// The uploaded avatar is always served from the same URL, so after a re-upload the
// browser would keep showing the cached image unless the URL changes.

const AVATAR_VERSION_PREFIX = "cs_avatar_v_";

function avatarVersion(id: string): string {
  return localStorage.getItem(AVATAR_VERSION_PREFIX + id) ?? "0";
}

function bumpAvatarVersion(id: string) {
  localStorage.setItem(AVATAR_VERSION_PREFIX + id, String(Date.now()));
}

export const api = {
  auth: {
    status: () =>
      getJson<{ authRequired: boolean; authenticated: boolean }>("/auth/status"),
    // dedicated fetch: here a 401 means "wrong password", not "login needed".
    login: async (password: string) => {
      const res = await fetch(`${BASE}/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ password }),
      });
      if (!res.ok) {
        const body = (await res.json().catch(() => ({}))) as { error?: string };
        throw new Error(body.error ?? "Sign-in failed");
      }
      return (await res.json()) as { authenticated: boolean };
    },
    logout: () => request<{ authenticated: boolean }>("/auth/logout", { method: "POST" }),
  },

  providers: () => getJson<ProviderInfo[]>("/providers"),

  home: (providerId: string, page = 1) =>
    getJson<HomeResponse>(`/providers/${providerId}/home?page=${page}`),

  search: (q: string, provider?: string) =>
    getJson<SearchResponse>(
      `/search?q=${encodeURIComponent(q)}${provider ? `&provider=${provider}` : ""}`,
    ),

  detail: (providerId: string, id: string) =>
    getJson<MediaDetail>(
      `/providers/${providerId}/detail?id=${encodeURIComponent(id)}`,
    ),

  links: (providerId: string, id: string) =>
    getJson<StreamLink[]>(
      `/providers/${providerId}/links?id=${encodeURIComponent(id)}`,
    ),

  // ---- Extension management ----
  extensions: {
    repos: () => getJson<RepositoryRef[]>("/extensions/repos"),
    addRepo: (url: string) =>
      request<RepositoryRef>("/extensions/repos", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url }),
      }),
    removeRepo: (url: string) =>
      request<{ removed: string }>(
        `/extensions/repos?url=${encodeURIComponent(url)}`,
        { method: "DELETE" },
      ),
    catalog: () => getJson<AvailablePlugin[]>("/extensions"),
    installed: () => getJson<InstalledExtension[]>("/extensions/installed"),
    install: (internalName: string) =>
      request<InstallResult>(`/extensions/${internalName}/install`, { method: "POST" }),
    update: (internalName: string) =>
      request<InstallResult | { upToDate: boolean }>(
        `/extensions/${internalName}/update`,
        { method: "POST" },
      ),
    uninstall: (internalName: string) =>
      request<{ uninstalled: string }>(`/extensions/${internalName}`, {
        method: "DELETE",
      }),
  },

  // ---- Profiles (multi-user) ----
  profiles: {
    list: () => getJson<Profile[]>("/profiles"),
    create: (name: string, color?: string, avatar?: string) =>
      request<Profile>("/profiles", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, color, avatar }),
      }),
    update: (id: string, req: UpdateProfileRequest) =>
      request<Profile>(`/profiles/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(req),
      }),
    remove: (id: string) =>
      request<{ deleted: string }>(`/profiles/${id}`, { method: "DELETE" }),
    uploadAvatar: async (id: string, file: File) => {
      const form = new FormData();
      form.append("file", file);
      const profile = await request<Profile>(`/profiles/${id}/avatar`, { method: "POST", body: form });
      bumpAvatarVersion(id);
      return profile;
    },
    deleteAvatar: async (id: string) => {
      const profile = await request<Profile>(`/profiles/${id}/avatar`, { method: "DELETE" });
      bumpAvatarVersion(id);
      return profile;
    },
    avatarUrl: (id: string) => `${BASE}/profiles/${id}/avatar?v=${avatarVersion(id)}`,
  },

  // ---- User library, per-profile via X-Profile-Id ----
  library: {
    watchlist: () =>
      request<LibraryItem[]>("/library/watchlist", { headers: profileHeaders() }),
    addWatchlist: (item: Omit<LibraryItem, "addedAt">) =>
      request<LibraryItem>("/library/watchlist", {
        method: "POST",
        headers: profileHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify(item),
      }),
    removeWatchlist: (providerId: string, mediaId: string) =>
      request<{ removed: string }>(
        `/library/watchlist?providerId=${encodeURIComponent(providerId)}&mediaId=${encodeURIComponent(mediaId)}`,
        { method: "DELETE", headers: profileHeaders() },
      ),
    continueWatching: () =>
      request<HistoryEntry[]>("/library/history?continue=true", {
        headers: profileHeaders(),
      }),
    /** Media fully watched (every episode finished), most recent first. */
    completed: () =>
      request<HistoryEntry[]>("/library/history?completed=true", {
        headers: profileHeaders(),
      }),
    // 204 → no saved position: return null.
    progress: async (providerId: string, episodeId: string) => {
      const res = await fetch(
        `${BASE}/library/progress?providerId=${encodeURIComponent(providerId)}&episodeId=${encodeURIComponent(episodeId)}`,
        { headers: profileHeaders() },
      );
      if (res.status === 204) return null;
      if (!res.ok) throw new Error(`Request failed (${res.status})`);
      return (await res.json()) as HistoryEntry;
    },
    /** All progress entries of a series/movie (for the "resume SxEy" in the detail page). */
    mediaProgress: (providerId: string, mediaId: string) =>
      request<HistoryEntry[]>(
        `/library/media-progress?providerId=${encodeURIComponent(providerId)}&mediaId=${encodeURIComponent(mediaId)}`,
        { headers: profileHeaders() },
      ),
    recordProgress: (req: ProgressRequest) =>
      request<HistoryEntry>("/library/progress", {
        method: "POST",
        headers: profileHeaders({ "Content-Type": "application/json" }),
        body: JSON.stringify(req),
      }),
  },
};

/**
 * Streaming proxy URL: the browser cannot set Referer/User-Agent, so every stream
 * goes through /api/v1/stream, which injects the StreamLink's headers.
 */
export function streamProxyUrl(link: StreamLink): string {
  const params = new URLSearchParams({ url: link.url });
  if (Object.keys(link.headers ?? {}).length > 0) {
    params.set("headers", JSON.stringify(link.headers));
  }
  return `${BASE}/stream?${params.toString()}`;
}
