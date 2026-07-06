# Installation guide

How to self-host Cloudstream Web. Target scenario: a home server / LAN (a container or VM you
control), not a public service — see [Security and scope](#security-and-scope).

> **Read this first — the repository ships with no extensions.**
> Cloudstream Web is content-neutral: it bundles no sources and no extensions. A freshly built
> instance runs correctly but has **zero providers**, so the Home page shows an empty state until
> you make an extension executable. Getting content is a deliberate, manual step described in
> [Adding content](#adding-content). If you only deploy the image and stop there, that is expected
> behaviour, not a bug.

## Option A — Docker (recommended)

Prerequisites: **Docker** and the **Docker Compose plugin**.

```bash
git clone https://github.com/Van-Helsing46/Cloudstream-Web.git
cd Cloudstream-Web

# 1. Configure (at least the auth secret; set a password to protect the instance)
cp backend/.env.example .env
#   edit .env → set AUTH_SECRET to a long random string, and AUTH_PASSWORD if you want a login

# 2. Build and start (single container: backend serves the API and the static frontend)
docker compose up -d --build

# 3. Verify
curl http://localhost:8080/health          # {"status":"ok"}
#   then open http://<host>:8080 in a browser
```

The build is multi-stage (frontend with Node, backend fat-jar with Gradle, JRE runtime), so the
first `up --build` takes a few minutes and needs Internet access (it resolves the Cloudstream JVM
library from JitPack). Subsequent starts are instant.

Persistent data (installed-extension state, profiles, watchlist/history) lives in the
`cloudstream-web-data` Docker volume mounted at `/data`, so it survives `up --build`, restarts and
redeploys.

### Updating

```bash
git pull
docker compose up -d --build     # the data volume is preserved
```

### Backup

Everything worth keeping is in the volume:

```bash
docker run --rm -v cloudstream-web_cloudstream-web-data:/data -v "$PWD":/backup \
  alpine tar czf /backup/cloudstream-web-data.tar.gz -C /data .
```

## Option B — Run from source (development)

Prerequisites: **JDK 21+**, **Node.js 20+**.

```bash
# Backend → http://localhost:8080
cd backend && ./gradlew run

# Frontend (separate terminal) → http://localhost:5173 (proxies /api to :8080)
cd frontend && npm install && npm run dev
```

## Configuration

All variables are optional with sensible defaults — full reference in
[`backend/.env.example`](backend/.env.example). The ones you are most likely to set:

| Variable | Default | Description |
|---|---|---|
| `AUTH_PASSWORD` | *(empty)* | Instance login password. **Empty = open access** (dev / trusted LAN only). |
| `AUTH_SECRET` | *(insecure default)* | Signs the session cookie — **change it** to a long random value. |
| `PORT` | `8080` | Exposed port. |
| `CLOUDSTREAM_WEB_DATA` | `/data` (container) | Persistent data directory. |
| `SEARCH_TIMEOUT_MS` | `15000` | Per-provider timeout for aggregated search. |

Generate a good secret with `openssl rand -hex 32`.

## Adding content

The runtime does not execute `.cs3` files directly (they are Android DEX). To make an extension
usable you recompile its Kotlin source against the Cloudstream JVM library and register it in the
runtime. The full walkthrough is in
[`backend/src/main/kotlin/com/cloudstreamweb/extensions/README.md`](backend/src/main/kotlin/com/cloudstreamweb/extensions/README.md).
In short:

1. Drop the extension's Kotlin source into `backend/src/main/kotlin/com/cloudstreamweb/extensions/bundled/`.
2. Register its factory in `BundledExtensionRuntime` with the `internalName` from the repository manifest.
3. Rebuild (`docker compose up -d --build`).
4. In the UI, open **Extensions**, add the repository that lists it, and install it — it will now
   activate (`runtimeSupported: true`).

Which extensions you add, and how you use them, is entirely your responsibility.

## Security and scope

Designed for **personal LAN use**. The auth is a single instance password and the streaming proxy
ships a basic SSRF guard (loopback/private-IP blocking) tuned for that scenario. If you expose it
beyond your LAN, put a **TLS reverse proxy** (Caddy, Traefik, nginx) in front of it — the app sets
the cookie `Secure` flag when the request arrives over HTTPS — and consider a VPN instead of a
public port. Do not treat it as a hardened public service.

## Troubleshooting

- **Home says "No active extensions".** Expected on a fresh install — see [Adding content](#adding-content).
- **First build fails resolving `library-jvm`.** The build needs Internet to reach JitPack; retry
  once its first build for that coordinate has completed, or check outbound network access.
- **`./gradlew: not found` / permission errors in Docker on Windows checkouts.** Ensure `gradlew`
  keeps LF line endings (a CRLF shebang breaks it inside the Linux build stage).
