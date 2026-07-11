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

A browser can't run `.cs3` files (Android DEX bytecode), so the **backend** runs them. When you add a repository and install an extension from the UI, the backend downloads and sha256-verifies the `.cs3`, then makes it executable on the JVM through a **layered runtime** that tries, in order:

1. **Bundled** — a curated, in-tree recompiled version, if one exists (the public repo ships none).
2. **Recompile from source** — fetches the extension's public Kotlin source from its repository (**GitHub / GitLab / Gitea·Forgejo**, including source kept on a side branch) and compiles it *in-process* against Cloudstream's official JVM library (`com.github.recloudstream.cloudstream:library-jvm`, via JitPack) plus a few compatibility shims. Widest coverage, and it sidesteps ABI drift baked into the shipped bytecode.
3. **DEX→JVM conversion** — converts the `.cs3` bytecode to a JVM jar and loads it directly, as a fallback when source isn't available.

Whatever the path, the provider (`MainAPI`) is instantiated and adapted to the internal domain model. Android-only concerns are handled server-side or by shims: the plugin loader and settings UI are skipped, and `CloudflareKiller`/`WebView` have server-side stand-ins. Loaded code is screened first — extensions using process/exit/native APIs are refused (see **[SECURITY.md](SECURITY.md)**).

Caveats, honestly:

- the public repository **contains no extensions** (`extensions/bundled/` is empty) and there is no provider until you add a repo and install one;
- **most scraper extensions work**, but some don't run unmodified — providers whose constructor needs configuration that only the Android plugin supplied (e.g. multi-source IPTV), or that use Cloudstream *app* internals not present in `library-jvm` (account/sync). Those install but stay inactive (the UI tells you);
- the recompile path needs the extension's **source to be public** (the Cloudstream convention). The manual in-tree bundling route still exists for special cases — see [`backend/src/main/kotlin/com/cloudstreamweb/extensions/README.md`](backend/src/main/kotlin/com/cloudstreamweb/extensions/README.md).

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

On first launch the app is empty: the Home page points you to **Extensions**. To get content, open **Extensions**, add a repository (a Cloudstream `repo.json`/`plugins.json` URL) and install a provider — the backend makes it executable automatically (see above).

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

Built for **personal LAN use** (e.g. a container on a home server), not for public exposure: auth is a single instance password, and the proxy ships a basic SSRF guard (loopback/private IP blocking) tuned for that scenario. Extensions are third-party code executed in the backend, so installs are screened and container hardening matters — see **[SECURITY.md](SECURITY.md)** for the threat model and the recommended Docker hardening. If you expose it to the Internet, put a TLS reverse proxy in front.

## Credits and license

- [Cloudstream](https://github.com/recloudstream/cloudstream) (recloudstream) for the original app and the `library-jvm` the extensions are recompiled against.
- License: **GPL-3.0** (see [LICENSE](LICENSE)), same as the upstream project this derives from conceptually and links to at the library level.
