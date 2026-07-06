# Cloudstream Web

An **unofficial, self-hosted web port** of [Cloudstream](https://github.com/recloudstream/cloudstream) (the extension-based Android media player): a JVM backend runs the extensions and proxies the streams, a web frontend provides the UI from any browser on your LAN.

> ⚠️ **Disclaimer.** Personal project, not affiliated with recloudstream. Like the upstream app, Cloudstream Web is **content-neutral**: it does not include, host or distribute any source or any extension. The repository is "empty" by design: any extension you add, and whatever you do with it, is entirely your own responsibility.

## What it is (and why it is not a 1:1 port)

Cloudstream is an Android app whose sources are **compiled Kotlin extensions** (`.cs3`, DEX bytecode) loaded at runtime. A browser cannot execute that bytecode, cannot make cross-site requests (CORS), and cannot set headers such as `Referer`/`User-Agent` required by CDNs. Hence the **client-server** re-architecture:

```
┌──────────────────┐        REST /api/v1        ┌────────────────────────────┐
│  Frontend (web)  │ ─────────────────────────► │  Backend JVM (Kotlin/Ktor) │
│  React + Vite    │                            │  • extension runtime       │
│  hls.js player   │ ◄───────────────────────── │  • scraping (OkHttp/Jsoup) │
└────────┬─────────┘        JSON results        │  • streaming proxy         │
         │                                      └─────────────┬──────────────┘
         │        HLS/MP4 streams via proxy                   │
         └────────────────────────────────────────────────────┘
             the proxy injects the headers required by the sources
             and rewrites HLS manifests (variants, AES keys, subtitles)
```

Everything the Android app did on-device (running the extension, scraping, opening the stream with the right headers) is done here by the **backend**; the browser only ever talks to the backend.

## Features

- **Aggregated search** across all active providers (parallel and fault-tolerant: one broken provider does not block the others), per-provider home page, detail page with seasons/episodes and multiple sources.
- **HLS player** (hls.js) through the **streaming proxy**: per-request header injection, full manifest rewriting (variants, `EXT-X-KEY`/`MAP`/`MEDIA`, AES-128 keys), range requests/206, SSRF guard.
- **Extension management** from the UI: add Cloudstream repositories (`repo.json`/`plugins.json`), install/update/uninstall with sha256 verification, persisted state and reactivation on startup.
- **Netflix-style multi-user profiles** (no per-profile password) with **watchlist**, **watch history** and per-series **"continue watching"**: the player saves your position and resumes where you left off ("Resume S1E3"), with per-episode progress bars.
- **Single instance password auth** (HMAC-signed httpOnly session cookie) — designed for LAN exposure, not for running a public service.
- **Versioned REST API** (`/api/v1`) with an **OpenAPI** contract served at `/swagger`, structured logging (text or JSON), configuration entirely via environment variables.
- **Single-container deploy**: one Docker image with the backend also serving the static frontend.

## How extensions work here

The runtime **does not execute `.cs3` files directly** (they are Android DEX). The approach, validated in an early spike, is to recompile the extension's **Kotlin source** against Cloudstream's official JVM library (`com.github.recloudstream.cloudstream:library-jvm`, via JitPack) and instantiate it as a plain JVM class, adapted to the internal domain model.

This means that:

- the public repository **contains no extensions**: `extensions/bundled/` is empty and at runtime there is no provider until you add one;
- to make an extension executable you copy its source into `bundled/`, register its factory in `BundledExtensionRuntime` and rebuild — step-by-step guide in [`backend/src/main/kotlin/com/cloudstreamweb/extensions/README.md`](backend/src/main/kotlin/com/cloudstreamweb/extensions/README.md);
- installing from a repository via the UI downloads and versions the `.cs3` (metadata, hash, updates), but an extension is only *executable* if it is also present in the recompiled runtime (`runtimeSupported`).

On-demand source compilation (or DEX→JAR conversion) is a possible evolution.

## Repository layout

| Path        | Contents |
|-------------|----------|
| `backend/`  | Kotlin + Ktor 3 (Netty), JDK 21+: REST API, extension runtime, streaming proxy, profiles/library (atomic JSON persistence in `data/`) |
| `frontend/` | React 18 + Vite + TypeScript: UI, hls.js player, TanStack Query |
| `Dockerfile` + `docker-compose.yml` | Single-container packaging |

## Quick start (development)

Prerequisites: **JDK 21+**, **Node.js 20+**.

```bash
# Backend → http://localhost:8080
cd backend
./gradlew run

# Frontend (separate terminal) → http://localhost:5173 (proxies /api to :8080)
cd frontend
npm install
npm run dev
```

On first launch the app is empty: the Home page points you to **Extensions**. To get actual content you need to bundle an extension (see above).

## Deploy (Docker)

```bash
cp backend/.env.example .env   # set at least AUTH_PASSWORD and AUTH_SECRET
docker compose up -d --build   # → http://<host>:8080
```

Persistent data (extension state, profiles, library) lives in the `cloudstream-web-data` volume; back it up to keep your library across reinstalls.

See **[INSTALL.md](INSTALL.md)** for the full self-hosting guide: updating, backup, configuration, and how to add content (the instance ships with no extensions).

### Configuration (environment variables)

All optional, with sensible defaults — full reference in [`backend/.env.example`](backend/.env.example):

| Variable | Default | Description |
|---|---|---|
| `PORT` / `HOST` | `8080` / `0.0.0.0` | Backend listen address |
| `CLOUDSTREAM_WEB_DATA` | `data` | Persistent data directory (extensions, profiles, library) |
| `AUTH_PASSWORD` | *(empty)* | Instance password; **empty = open access** (dev / trusted LAN only) |
| `AUTH_SECRET` | *(change it)* | Signs the session cookies |
| `CORS_HOSTS` | *(empty)* | Extra allowed origins (empty = same-origin) |
| `SEARCH_TIMEOUT_MS` | `15000` | Per-provider timeout for aggregated search |
| `FRONTEND_DIR` | *(empty)* | Static FE build to serve (set inside the container) |
| `LOG_FORMAT` | `text` | `text` (dev) or `json` (prod) |

## API

REST under `/api/v1`: providers (`/search`, `/providers/{id}/{home,detail,links}`), extensions (`/extensions/...`), profiles (`/profiles`), per-profile library (`/library/...`, `X-Profile-Id` header), proxy (`/stream?url&headers`). Interactive OpenAPI contract at **`/swagger`** (raw spec at `/openapi.yaml`).

## Security and scope

Built for **personal LAN use** (e.g. a container on a home server), not for public exposure: auth is a single instance password, and the proxy ships a basic SSRF guard (loopback/private IP blocking) tuned for that scenario. If you expose it to the Internet, put a TLS reverse proxy in front and consider additional hardening.

## Credits and license

- [Cloudstream](https://github.com/recloudstream/cloudstream) (recloudstream) for the original app and the `library-jvm` the extensions are recompiled against.
- License: **GPL-3.0** (see [LICENSE](LICENSE)), same as the upstream project this derives from conceptually and links to at the library level.
