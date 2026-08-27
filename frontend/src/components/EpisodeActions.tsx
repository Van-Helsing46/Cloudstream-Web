import { useEffect, useState, type MouseEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, downloadFileUrl, externalStreamUrl } from "../api/client";
import { copyToClipboard } from "../lib/clipboard";
import { useT } from "../i18n";
import type { Episode, StreamLink } from "../types";

type PendingAction = "copy" | "download";

const JOBS_KEY = "cs_download_jobs";

function readJobMap(): Record<string, string> {
  try {
    return JSON.parse(localStorage.getItem(JOBS_KEY) ?? "{}") as Record<string, string>;
  } catch {
    return {};
  }
}
function rememberJob(episodeId: string, jobId: string) {
  const map = readJobMap();
  map[episodeId] = jobId;
  localStorage.setItem(JOBS_KEY, JSON.stringify(map));
}
function forgetJob(episodeId: string) {
  const map = readJobMap();
  delete map[episodeId];
  localStorage.setItem(JOBS_KEY, JSON.stringify(map));
}

const RING_R = 8;
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_R;

/** Progress ring around the download icon; spins indeterminately while duration is unknown. */
function ProgressRing({ progress }: { progress: number | null }) {
  const indeterminate = progress == null;
  return (
    <svg
      className={indeterminate ? "episode-action-ring episode-action-ring-spin" : "episode-action-ring"}
      viewBox="0 0 20 20"
      width="20"
      height="20"
      aria-hidden="true"
    >
      <circle cx="10" cy="10" r={RING_R} className="episode-action-ring-track" />
      <circle
        cx="10"
        cy="10"
        r={RING_R}
        className="episode-action-ring-fill"
        strokeDasharray={RING_CIRCUMFERENCE}
        strokeDashoffset={indeterminate ? RING_CIRCUMFERENCE * 0.75 : RING_CIRCUMFERENCE * (1 - progress)}
      />
    </svg>
  );
}

/**
 * "Copy link" and "download" for a single episode row. Both resolve the episode's stream
 * links on demand (via [resolveLinks], shared/cached across the whole episode list) and,
 * when there's more than one source, ask the user to pick one via a small popover — the
 * same choice the player's source chips offer, just made up front instead of after playing.
 */
export function EpisodeActions({
  providerId,
  episode,
  filenameBase,
  resolveLinks,
  linksLoading,
}: {
  providerId: string;
  episode: Episode;
  filenameBase: string;
  resolveLinks: (episode: Episode) => Promise<StreamLink[]>;
  linksLoading: boolean;
}) {
  const t = useT();
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [choices, setChoices] = useState<{ action: PendingAction; links: StreamLink[] } | null>(null);
  const [copied, setCopied] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [jobId, setJobId] = useState<string | null>(() => readJobMap()[episode.id] ?? null);

  const job = useQuery({
    queryKey: ["download-job", jobId],
    queryFn: () => api.downloads.status(jobId as string),
    enabled: jobId != null,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === "READY" || status === "FAILED" || status === "CANCELED" ? false : 1000;
    },
    retry: false,
  });

  // The backend keeps jobs only in memory: after a restart the id is gone. Drop it instead
  // of polling a 404 forever, so the button falls back to "start a new download".
  useEffect(() => {
    if (jobId && job.isError) {
      forgetJob(episode.id);
      setJobId(null);
    }
  }, [job.isError, jobId, episode.id]);

  // Close the source picker on any click outside it (its own buttons stop propagation).
  useEffect(() => {
    if (!choices) return;
    const close = () => setChoices(null);
    document.addEventListener("click", close);
    return () => document.removeEventListener("click", close);
  }, [choices]);

  async function copyLink(link: StreamLink) {
    // The raw source URL, not the backend proxy: the proxy exists to inject headers
    // (Referer/User-Agent) that the *browser* can't set, but copying it out is meant to hand
    // the user the actual media URL for an external tool that manages its own headers.
    const ok = await copyToClipboard(link.url);
    if (ok) {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } else {
      setActionError(t("detail.downloadFailed"));
    }
  }

  async function startDownload(link: StreamLink) {
    if (!link.isM3u8) {
      // Progressive source: the browser can save it directly through the proxy, no backend job.
      const token = await api.streamToken();
      const url = externalStreamUrl(link, { token, filename: `${filenameBase}.mp4` });
      const a = document.createElement("a");
      a.href = url;
      a.download = `${filenameBase}.mp4`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      return;
    }
    const job = await api.downloads.start({
      providerId,
      episodeId: episode.id,
      url: link.url,
      headers: link.headers,
      isM3u8: true,
      filename: `${filenameBase}.mp4`,
    });
    rememberJob(episode.id, job.id);
    setJobId(job.id);
    // The backend may have handed back an existing job for this episode instead of a fresh
    // one (another tab/device already downloading it, or a still-cached finished file) — if
    // it's already done, grab the file right away instead of leaving the user to click again.
    if (job.status === "READY") {
      const token = await api.streamToken();
      window.location.href = downloadFileUrl(job.id, token);
    }
  }

  async function runAction(action: PendingAction, link: StreamLink) {
    if (action === "copy") await copyLink(link);
    else await startDownload(link);
  }

  async function trigger(action: PendingAction, e: MouseEvent) {
    e.stopPropagation();
    setActionError(null);

    if (action === "download") {
      const status = job.data?.status;
      if (status === "READY" && jobId) {
        const token = await api.streamToken();
        window.location.href = downloadFileUrl(jobId, token);
        return;
      }
      if (status === "RUNNING" || status === "QUEUED") return; // already in progress
    }

    setPending(action);
    try {
      const links = await resolveLinks(episode);
      if (links.length === 0) {
        setActionError(t("detail.noSource"));
        return;
      }
      if (links.length === 1) {
        await runAction(action, links[0]);
      } else {
        setChoices({ action, links });
      }
    } catch (err) {
      setActionError(err instanceof Error ? err.message : String(err));
    } finally {
      setPending(null);
    }
  }

  async function handleChoice(link: StreamLink) {
    if (!choices) return;
    const { action } = choices;
    setChoices(null);
    try {
      await runAction(action, link);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : String(err));
    }
  }

  const jobStatus = job.data?.status;
  const downloadActive = jobStatus === "RUNNING" || jobStatus === "QUEUED";
  const downloadReady = jobStatus === "READY";
  const downloadFailed = jobStatus === "FAILED";

  let downloadTitle = t("detail.download");
  if (downloadActive) downloadTitle = t("detail.downloadPreparing");
  else if (downloadReady) downloadTitle = t("detail.downloadReady");
  else if (downloadFailed) {
    downloadTitle = job.data?.error === "ffmpeg-missing" ? t("detail.ffmpegMissing") : t("detail.downloadFailed");
  }

  return (
    <div className="episode-actions" onClick={(e) => e.stopPropagation()}>
      {actionError && <span className="episode-action-error-text">{actionError}</span>}

      <button
        type="button"
        className="episode-action-btn"
        onClick={(e) => void trigger("copy", e)}
        title={copied ? t("detail.copied") : t("detail.copyLink")}
        aria-label={t("detail.copyLink")}
        disabled={linksLoading && pending === "copy"}
      >
        {copied ? "✓" : "🔗"}
      </button>

      <button
        type="button"
        className={downloadFailed ? "episode-action-btn episode-action-error" : "episode-action-btn"}
        onClick={(e) => void trigger("download", e)}
        title={downloadTitle}
        aria-label={downloadTitle}
        disabled={linksLoading && pending === "download"}
      >
        {downloadActive ? (
          <ProgressRing progress={job.data?.progress ?? null} />
        ) : downloadReady ? (
          "⤓"
        ) : downloadFailed ? (
          "⚠"
        ) : (
          "⬇"
        )}
      </button>

      {choices && (
        <div className="source-picker" role="menu">
          <span className="source-picker-label">{t("detail.chooseSource")}</span>
          <div className="chip-row">
            {choices.links.map((link, i) => (
              <button
                key={i}
                type="button"
                className="chip"
                onClick={(e) => {
                  e.stopPropagation();
                  void handleChoice(link);
                }}
              >
                {link.quality ?? t("detail.sourceN", { n: i + 1 })}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
