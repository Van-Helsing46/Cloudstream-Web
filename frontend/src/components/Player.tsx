import { useEffect, useRef } from "react";
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
export function Player({
  link,
  resumeAt,
  onProgress,
}: {
  link: StreamLink;
  resumeAt?: number;
  onProgress?: (positionSeconds: number, durationSeconds: number) => void;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const src = streamProxyUrl(link);

  // Always-fresh ref so listeners are not recreated on every render.
  const progressRef = useRef(onProgress);
  progressRef.current = onProgress;

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    let hls: Hls | undefined;
    if (link.isM3u8 && Hls.isSupported()) {
      hls = new Hls();
      hls.loadSource(src);
      hls.attachMedia(video);
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

    const report = () => {
      if (video.duration && !Number.isNaN(video.duration)) {
        progressRef.current?.(video.currentTime, video.duration);
      }
    };
    const interval = window.setInterval(() => {
      if (!video.paused) report();
    }, 10_000);
    video.addEventListener("pause", report);

    return () => {
      window.clearInterval(interval);
      video.removeEventListener("loadedmetadata", doResume);
      video.removeEventListener("pause", report);
      report(); // save the last position on unmount
      hls?.destroy();
    };
  }, [link, src, resumeAt]);

  return (
    <video
      ref={videoRef}
      controls
      autoPlay
      style={{ width: "100%", maxHeight: "70vh", background: "#000" }}
    />
  );
}
