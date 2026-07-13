import { useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { Player, type ProgressReason } from "../components/Player";
import type { Episode, HistoryEntry, StreamLink } from "../types";
import { useT } from "../i18n";
import { posterGradient } from "../lib/colors";
import { sortEpisodes } from "../lib/episodes";
import { useWatchlist } from "../hooks/useWatchlist";

/** Detail page: backdrop hero, poster/metadata, season pills, episode rows → play via proxy. */
export function DetailPage() {
  const t = useT();
  const { providerId = "" } = useParams();
  const [params, setSearchParams] = useSearchParams();
  const id = params.get("id") ?? "";
  const location = useLocation();
  const navigate = useNavigate();

  const qc = useQueryClient();
  const [playing, setPlaying] = useState<Episode | null>(null);
  const [links, setLinks] = useState<StreamLink[] | null>(null);
  const [current, setCurrent] = useState<StreamLink | null>(null);
  const [linkError, setLinkError] = useState<string | null>(null);
  const [loadingLinks, setLoadingLinks] = useState(false);
  const [resumeAt, setResumeAt] = useState<number>(0);
  const [selectedSeason, setSelectedSeason] = useState<number | null>(null);

  // Fullscreen lives on a wrapper around the whole player section (not the <video>
  // itself), so the "next episode" overlay stays visible in fullscreen and survives
  // the Player unmount/remount that happens when play() swaps episodes.
  const playerShellRef = useRef<HTMLDivElement>(null);
  const [isFullscreen, setIsFullscreen] = useState(false);
  useEffect(() => {
    const handler = () => setIsFullscreen(document.fullscreenElement === playerShellRef.current);
    document.addEventListener("fullscreenchange", handler);
    return () => document.removeEventListener("fullscreenchange", handler);
  }, []);
  function toggleFullscreen() {
    if (document.fullscreenElement) void document.exitFullscreen();
    else void playerShellRef.current?.requestFullscreen();
  }

  // "Next episode" countdown, shown on the player's `ended` event.
  const NEXT_EPISODE_COUNTDOWN = 10;
  const [nextPrompt, setNextPrompt] = useState<Episode | null>(null);
  const [countdown, setCountdown] = useState(NEXT_EPISODE_COUNTDOWN);
  const countdownTimer = useRef<number | undefined>(undefined);
  function clearCountdown() {
    if (countdownTimer.current !== undefined) {
      window.clearInterval(countdownTimer.current);
      countdownTimer.current = undefined;
    }
  }
  function cancelNextPrompt() {
    clearCountdown();
    setNextPrompt(null);
  }
  function startNextEpisodeCountdown(next: Episode) {
    setNextPrompt(next);
    let remaining = NEXT_EPISODE_COUNTDOWN;
    setCountdown(remaining);
    clearCountdown();
    countdownTimer.current = window.setInterval(() => {
      remaining -= 1;
      setCountdown(remaining);
      if (remaining <= 0) {
        clearCountdown();
        setNextPrompt(null);
        void play(next);
      }
    }, 1000);
  }
  function playNextNow() {
    if (!nextPrompt) return;
    const next = nextPrompt;
    clearCountdown();
    setNextPrompt(null);
    void play(next);
  }
  useEffect(() => () => clearCountdown(), []); // stop the timer on unmount

  // A different title: drop the previous one's player/season state.
  useEffect(() => {
    setPlaying(null);
    setLinks(null);
    setCurrent(null);
    setLinkError(null);
    setSelectedSeason(null);
    cancelNextPrompt();
  }, [providerId, id]);

  const detail = useQuery({
    queryKey: ["detail", providerId, id],
    queryFn: () => api.detail(providerId, id),
    enabled: providerId.length > 0 && id.length > 0,
  });

  const providers = useQuery({ queryKey: ["providers"], queryFn: api.providers });

  const { isInWatchlist, toggle: toggleWatchlistItem } = useWatchlist();
  const inWatchlist = isInWatchlist(providerId, id);

  // Per-series progress: map episodeId → entry (for "Resume SxEy" and episode badges).
  const mediaProgress = useQuery({
    queryKey: ["library", "media-progress", providerId, id],
    queryFn: () => api.library.mediaProgress(providerId, id),
    enabled: providerId.length > 0 && id.length > 0,
  });
  const progressByEpisode = useMemo(() => {
    const m = new Map<string, HistoryEntry>();
    for (const e of mediaProgress.data ?? []) m.set(e.episodeId, e);
    return m;
  }, [mediaProgress.data]);
  // Most recent unfinished entry = "Resume" candidate (history is already recency-ordered).
  const resumeEntry = (mediaProgress.data ?? []).find(
    (e) => !e.durationSeconds || e.positionSeconds / e.durationSeconds < 0.9,
  );

  // ?play=1 (from a search-result "play" button): auto-open the player once the
  // detail + progress are ready, then drop the param so a refresh doesn't replay it.
  const autoplayDone = useRef(false);
  useEffect(() => {
    autoplayDone.current = false;
  }, [providerId, id]);
  useEffect(() => {
    if (autoplayDone.current) return;
    if (params.get("play") !== "1") return;
    if (!detail.data || !mediaProgress.isFetched) return;
    autoplayDone.current = true;
    const media = detail.data;
    const target = resumeEntry
      ? media.episodes.find((e) => e.id === resumeEntry.episodeId) ?? media.episodes[0]
      : media.episodes[0];
    if (target) void play(target);
    const next = new URLSearchParams(params);
    next.delete("play");
    setSearchParams(next, { replace: true });
  }, [params, detail.data, mediaProgress.isFetched, resumeEntry, setSearchParams]);

  async function toggleWatchlist() {
    const media = detail.data;
    if (!media) return;
    await toggleWatchlistItem({
      providerId,
      mediaId: id,
      title: media.title,
      type: media.type,
      posterUrl: media.posterUrl,
      year: media.year,
    });
  }

  // Episodes grouped by season (movies have a single "episode").
  const flatEpisodes = useMemo(() => sortEpisodes(detail.data?.episodes ?? []), [detail.data]);
  const seasons = useMemo(() => {
    const groups = new Map<number, Episode[]>();
    for (const ep of flatEpisodes) {
      const key = ep.season ?? 0;
      (groups.get(key) ?? groups.set(key, []).get(key)!).push(ep);
    }
    return [...groups.entries()].sort((a, b) => a[0] - b[0]);
  }, [flatEpisodes]);

  function handlePlaybackEnded() {
    const ep = playing;
    if (!ep) return;
    const idx = flatEpisodes.findIndex((e) => e.id === ep.id);
    const next = idx >= 0 && idx < flatEpisodes.length - 1 ? flatEpisodes[idx + 1] : null;
    if (next) startNextEpisodeCountdown(next);
  }

  async function play(episode: Episode) {
    cancelNextPrompt();
    setPlaying(episode);
    setLinks(null);
    setCurrent(null);
    setLinkError(null);
    setResumeAt(0);
    setLoadingLinks(true);
    try {
      // Saved position (resume) and sources in parallel.
      const [saved, resolved] = await Promise.all([
        api.library.progress(providerId, episode.id).catch(() => null),
        api.links(providerId, episode.id),
      ]);
      if (resolved.length === 0) {
        setLinkError(t("detail.noSource"));
        return;
      }
      setResumeAt(saved?.positionSeconds ?? 0);
      setLinks(resolved);
      setCurrent(resolved[0]);
    } catch (e) {
      setLinkError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoadingLinks(false);
    }
  }

  // Persists the position (called by the Player); mediaId = the page's id. Also
  // invalidates the progress queries so badges/bars update without a page refresh —
  // immediately for pause/unmount/ended, throttled for the 10s heartbeat.
  const lastInvalidateAt = useRef(0);
  const INVALIDATE_THROTTLE_MS = 30_000;
  async function saveProgress(position: number, duration: number, reason: ProgressReason) {
    const ep = playing;
    const media = detail.data;
    if (!ep || !media) return;
    await api.library.recordProgress({
      providerId,
      mediaId: id,
      episodeId: ep.id,
      title: media.title,
      episodeName: ep.name,
      season: ep.season,
      episode: ep.episode,
      posterUrl: media.posterUrl,
      positionSeconds: position,
      durationSeconds: duration,
      totalEpisodes: media.episodes.length,
    });
    const now = Date.now();
    if (reason === "interval" && now - lastInvalidateAt.current < INVALIDATE_THROTTLE_MS) return;
    lastInvalidateAt.current = now;
    void qc.invalidateQueries({ queryKey: ["library", "media-progress", providerId, id] });
    void qc.invalidateQueries({ queryKey: ["library", "continue"] });
    void qc.invalidateQueries({ queryKey: ["library", "completed"] });
  }

  function goBack() {
    if (location.key !== "default") navigate(-1);
    else navigate("/");
  }

  if (detail.isLoading) return <p className="muted">{t("detail.loading")}</p>;
  if (detail.isError) return <p className="error">{String(detail.error)}</p>;
  if (!detail.data) return null;

  const media = detail.data;
  const isMovie = media.episodes.length <= 1;
  const providerLabel = providers.data?.find((p) => p.id === media.providerId)?.name ?? media.providerId;

  const playTarget = resumeEntry
    ? media.episodes.find((e) => e.id === resumeEntry.episodeId) ?? media.episodes[0]
    : media.episodes[0];
  const playLabel = resumeEntry
    ? `${t("detail.resume")}${
        resumeEntry.season != null && resumeEntry.episode != null
          ? ` S${resumeEntry.season}E${resumeEntry.episode}`
          : ""
      }`
    : t("detail.play");

  const currentSeasonKey = selectedSeason ?? seasons[0]?.[0] ?? 0;
  const currentEpisodes = seasons.find(([key]) => key === currentSeasonKey)?.[1] ?? [];

  return (
    <>
      <div
        className="detail-hero"
        style={
          media.posterUrl
            ? { backgroundImage: `url("${media.posterUrl}")` }
            : { background: posterGradient(media.title) }
        }
      >
        <div
          className="detail-hero-bg"
          style={
            media.posterUrl
              ? { backgroundImage: `url("${media.posterUrl}")` }
              : { background: posterGradient(media.title) }
          }
        />
        <div className="detail-hero-scrim" />
        <button className="detail-back-btn" onClick={goBack}>
          ← {t("detail.back")}
        </button>
      </div>

      <div className="detail-body">
        <div className="detail-header">
          <div className="detail-poster">
            {media.posterUrl ? (
              <img src={media.posterUrl} alt={media.title} />
            ) : (
              <div className="media-card-placeholder" style={{ background: posterGradient(media.title) }}>
                <span className="media-card-placeholder-title">{media.title}</span>
              </div>
            )}
          </div>
          <div className="detail-info">
            <h1 className="detail-title">{media.title}</h1>
            <div className="detail-tags">
              <span className="detail-tag">{media.type}</span>
              {media.year != null && <span className="detail-tag">{media.year}</span>}
              <span className="detail-tag detail-tag-accent">{providerLabel}</span>
            </div>
            {media.plot && <p className="detail-plot">{media.plot}</p>}
            <div className="detail-actions">
              {playTarget && (
                <button className="btn-primary" onClick={() => play(playTarget)}>
                  ▶ {playLabel}
                </button>
              )}
              <button className="btn-secondary" onClick={toggleWatchlist}>
                {inWatchlist ? t("detail.inWatchlist") : t("detail.addToWatchlist")}
              </button>
            </div>
          </div>
        </div>

        {playing && (
          <div ref={playerShellRef} className="player-shell">
            <section className="player-section">
              {current && (
                <Player
                  link={current}
                  resumeAt={resumeAt}
                  onProgress={saveProgress}
                  onEnded={handlePlaybackEnded}
                />
              )}
              {loadingLinks && <p className="muted">{t("detail.resolvingSource")}</p>}
              {linkError && <p className="error">{linkError}</p>}
              {links && links.length > 1 && (
                <div className="chip-row">
                  <span className="muted">{t("detail.source")}</span>
                  {links.map((l, i) => (
                    <button
                      key={i}
                      className={l === current ? "chip chip-active" : "chip"}
                      onClick={() => setCurrent(l)}
                    >
                      {l.quality ?? t("detail.sourceN", { n: i + 1 })}
                    </button>
                  ))}
                </div>
              )}
            </section>

            {current && (
              // Native fullscreen (via the <video> controls) only fullscreens the video
              // itself and hides this overlay, so fullscreen must go through this button.
              <button
                type="button"
                className="player-fullscreen-btn"
                onClick={toggleFullscreen}
                title={t("detail.fullscreen")}
                aria-label={t("detail.fullscreen")}
              >
                {isFullscreen ? "⤡" : "⤢"}
              </button>
            )}

            {nextPrompt && (
              <div className="next-episode-overlay">
                <div className="next-episode-card">
                  <span className="next-episode-label">{t("detail.nextEpisode")}</span>
                  <span className="next-episode-title">
                    {nextPrompt.episode != null ? `${nextPrompt.episode}. ` : ""}
                    {nextPrompt.name ?? t("detail.episodeFallback", { n: nextPrompt.episode ?? 0 })}
                  </span>
                  <div className="next-episode-actions">
                    <button className="btn-primary" onClick={playNextNow}>
                      {t("detail.playNow")} ({countdown}s)
                    </button>
                    <button className="btn-secondary" onClick={cancelNextPrompt}>
                      {t("detail.cancel")}
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {!isMovie && (
          <div className="detail-episodes">
            {seasons.length > 1 && (
              <div className="chip-row">
                {seasons.map(([season]) => (
                  <button
                    key={season}
                    className={season === currentSeasonKey ? "chip chip-active" : "chip"}
                    onClick={() => setSelectedSeason(season)}
                  >
                    {t("detail.season", { n: season })}
                  </button>
                ))}
              </div>
            )}
            <div className="episode-rows">
              {currentEpisodes.map((ep, i) => {
                const active = playing === ep;
                const prog = progressByEpisode.get(ep.id);
                const pct = prog?.durationSeconds
                  ? Math.min(100, Math.round((prog.positionSeconds / prog.durationSeconds) * 100))
                  : 0;
                const badge = pct >= 90 ? t("detail.badgeWatched") : pct > 0 ? t("detail.badgeInProgress") : "";
                return (
                  <button
                    key={`${ep.id}-${i}`}
                    className={active ? "episode-row active" : "episode-row"}
                    onClick={() => play(ep)}
                  >
                    <div className="episode-thumb">
                      {ep.posterUrl ? (
                        <img src={ep.posterUrl} alt="" loading="lazy" />
                      ) : (
                        <div className="episode-thumb-play" aria-hidden="true">
                          ▶
                        </div>
                      )}
                      <div className="episode-bar">
                        <span className="episode-bar-fill" style={{ width: `${pct}%` }} />
                      </div>
                    </div>
                    <div className="episode-info">
                      <span className="episode-title">
                        {ep.episode != null ? `${ep.episode}. ` : ""}
                        {ep.name ?? t("detail.episodeFallback", { n: i + 1 })}
                      </span>
                      {ep.description && <p className="episode-desc">{ep.description}</p>}
                    </div>
                    {badge && <span className="episode-badge">{badge}</span>}
                  </button>
                );
              })}
            </div>
          </div>
        )}
      </div>
    </>
  );
}
