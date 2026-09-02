import { forwardRef, useEffect, useRef, type MutableRefObject } from "react";
import Hls from "hls.js";
import { streamProxyUrl } from "../api/client";
import type { StreamLink } from "../types";

/**
 * Video player. Every stream goes through the backend proxy (/api/v1/stream), which injects
 * the StreamLink's headers (Referer/User-Agent/tokens): the browser cannot do it on its own.
 * hls.js for .m3u8, native <video> for MP4 (or Safari, which has native HLS).
 *
 * Resume: `resumeAt` seeks the video on start; `onProgress` is called periodically
 * (and on pause) with the current position/duration, so the page can persist them.
 */
export type ProgressReason = "interval" | "pause" | "unmount" | "ended";

type PlayerProps = {
  link: StreamLink;
  resumeAt?: number;
  onProgress?: (positionSeconds: number, durationSeconds: number, reason: ProgressReason) => void;
  onEnded?: () => void;
  /** Shown in the OS media-session UI (lock screen / notification). */
  mediaTitle?: string;
  artworkUrl?: string;
};

// The <video> ref is forwarded so DetailPage can fall back to the legacy
// video.webkitEnterFullscreen() on iOS Safari, which has no Fullscreen API for other elements.
export const Player = forwardRef<HTMLVideoElement, PlayerProps>(function Player(
  { link, resumeAt, onProgress, onEnded, mediaTitle, artworkUrl },
  forwardedRef,
) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const src = streamProxyUrl(link);

  // OS media controls (Android notification / lock screen, macOS Now Playing): show the
  // media name instead of the proxy URL, and wire the transport buttons to the <video>.
  useEffect(() => {
    const video = videoRef.current;
    const ms = navigator.mediaSession;
    if (!video || !ms) return;
    if (mediaTitle && "MediaMetadata" in window) {
      ms.metadata = new MediaMetadata({
        title: mediaTitle,
        artwork: artworkUrl ? [{ src: artworkUrl }] : [],
      });
    }
    const set = (action: MediaSessionAction, handler: (() => void) | null) => {
      try {
        ms.setActionHandler(action, handler);
      } catch {
        /* action not supported by this browser */
      }
    };
    set("play", () => void video.play());
    set("pause", () => video.pause());
    set("seekbackward", () => {
      video.currentTime = Math.max(0, video.currentTime - 10);
    });
    set("seekforward", () => {
      video.currentTime = Math.min(video.duration || Infinity, video.currentTime + 10);
    });
    set("seekto", ((d: MediaSessionActionDetails) => {
      if (d.seekTime != null) video.currentTime = d.seekTime;
    }) as () => void);
    return () => {
      for (const a of ["play", "pause", "seekbackward", "seekforward", "seekto"] as const) set(a, null);
    };
  }, [mediaTitle, artworkUrl]);

  // Always-fresh refs so listeners are not recreated on every render.
  const progressRef = useRef(onProgress);
  progressRef.current = onProgress;
  const endedRef = useRef(onEnded);
  endedRef.current = onEnded;

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    let hls: Hls | undefined;
    let retryTimer: number | undefined;
    if (link.isM3u8 && Hls.isSupported()) {
      hls = new Hls();
      hls.loadSource(src);
      hls.attachMedia(video);

      // hls.js does not retry a fatal error on its own — left alone, a single dropped segment
      // (a transient upstream/proxy hiccup) permanently stalls playback until the page is
      // reloaded. Recover the two recoverable fatal error types with a capped, backoff retry;
      // give up only after repeated failures in a short window (a real network/media error
      // resets the count once loading actually resumes, so a later unrelated blip gets its own
      // fresh attempts instead of inheriting an exhausted counter from hours earlier).
      const MAX_RETRIES = 4;
      let retries = 0;
      hls.on(Hls.Events.FRAG_LOADED, () => {
        retries = 0;
      });
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (!data.fatal) return;
        if (retries >= MAX_RETRIES) {
          hls?.destroy();
          return;
        }
        const delayMs = 1000 * 2 ** retries;
        retries += 1;
        switch (data.type) {
          case Hls.ErrorTypes.NETWORK_ERROR:
            retryTimer = window.setTimeout(() => hls?.startLoad(), delayMs);
            break;
          case Hls.ErrorTypes.MEDIA_ERROR:
            retryTimer = window.setTimeout(() => hls?.recoverMediaError(), delayMs);
            break;
          default:
            hls?.destroy();
        }
      });
    } else {
      video.src = src;
    }

    let resumed = false;
    const doResume = () => {
      if (!resumed && resumeAt && resumeAt > 0 && video.duration) {
        video.currentTime = Math.min(resumeAt, video.duration - 1);
        resumed = true;
      }
    };
    video.addEventListener("loadedmetadata", doResume);

    const report = (reason: ProgressReason) => {
      if (video.duration && !Number.isNaN(video.duration)) {
        progressRef.current?.(video.currentTime, video.duration, reason);
      }
    };
    const onInterval = () => {
      if (!video.paused) report("interval");
    };
    const onPause = () => report("pause");
    const handleEnded = () => {
      report("ended");
      endedRef.current?.();
    };
    const interval = window.setInterval(onInterval, 10_000);
    video.addEventListener("pause", onPause);
    video.addEventListener("ended", handleEnded);

    return () => {
      window.clearInterval(interval);
      if (retryTimer !== undefined) window.clearTimeout(retryTimer);
      video.removeEventListener("loadedmetadata", doResume);
      video.removeEventListener("pause", onPause);
      video.removeEventListener("ended", handleEnded);
      report("unmount"); // save the last position on unmount
      hls?.destroy();
    };
  }, [link, src, resumeAt]);

  return (
    <video
      ref={(el) => {
        videoRef.current = el;
        if (typeof forwardedRef === "function") forwardedRef(el);
        else if (forwardedRef) (forwardedRef as MutableRefObject<HTMLVideoElement | null>).current = el;
      }}
      controls
      autoPlay
      // playsInline (+ the legacy webkit- prefix) keeps iOS Safari playing inline instead of
      // switching to its own system-wide fullscreen player, which would bypass .player-shell
      // entirely (custom fullscreen button, next-episode overlay).
      playsInline
      webkit-playsinline="true"
      style={{ width: "100%", maxHeight: "70vh", background: "#000" }}
    />
  );
});
