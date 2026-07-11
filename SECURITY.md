# Security & hardening

Cloudstream Web is built for **personal, self-hosted use on a trusted LAN** (e.g. a container on
a home server) — not as a public, multi-tenant service. This document describes the threat model
and the recommended hardening.

## Threat model

The instance runs **third-party extensions**: code you install from repositories *you* choose. An
extension is downloaded (`.cs3`) and either its DEX is converted to JVM bytecode or its public
source is recompiled, then loaded and executed **in the backend JVM** to scrape sites and resolve
streams. Executing third-party code is the main risk surface: a malicious or compromised extension
runs with the backend's privileges and can, in principle, reach the network and the process's own
data (profiles, library, the auth secret).

True in-JVM sandboxing is not available (the Java `SecurityManager` is deprecated and being
removed), so isolation is **layered** and the container is the boundary that actually contains a
hostile extension.

## What the app does for you

- **Static bytecode screening.** Before loading, every extension's classes are scanned and
  **rejected** if they use APIs a content scraper never legitimately needs and that sabotage would
  rely on: spawning OS processes (`Runtime.exec`, `ProcessBuilder`), killing the JVM
  (`System.exit`, `Runtime.halt`) or loading native libraries. Reflection is logged. This is
  defense-in-depth, **not a sandbox** — a determined attacker can defeat any static check.
- **Call timeouts.** Provider calls (`search`, `home`, `detail`, `links`) are time-bounded so a
  hung or misbehaving extension can't tie up requests indefinitely.
- **Hash-verified installs** (sha256) and a **streaming proxy with an SSRF guard** (blocks
  loopback/private targets).
- **Instance auth**: a single password with an HMAC-signed, httpOnly session cookie.

## Recommended container hardening (the real boundary)

Run the container with least privilege. With `docker run`:

```bash
docker run -d --name cloudstream-web \
  -p 127.0.0.1:8080:8080 \
  -v cloudstream-web-data:/data \
  --read-only --tmpfs /tmp \
  --cap-drop=ALL \
  --security-opt=no-new-privileges \
  --memory=1g --cpus=2 --pids-limit=512 \
  cloudstream-web
```

Or in `docker-compose.yml`:

```yaml
services:
  cloudstream-web:
    read_only: true
    tmpfs: [/tmp]
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    mem_limit: 1g
    pids_limit: 512
```

Notes:
- `--read-only` + a writable volume only for the data dir (`CLOUDSTREAM_WEB_DATA`) means a hostile
  extension cannot modify the app or the host filesystem, only its own data volume.
- Restrict **outbound** network if you can (egress firewall / a dedicated Docker network): a
  scraper needs to reach the sites it scrapes, nothing else.
- `--cap-drop=ALL` and `no-new-privileges` remove privilege-escalation avenues.
- Resource limits (`--memory`, `--cpus`, `--pids-limit`) bound a runaway or abusive extension.

## Exposure

- Set a strong **`AUTH_PASSWORD`** and a unique **`AUTH_SECRET`** (see [`backend/.env.example`](backend/.env.example)).
- Do **not** expose the port directly to the Internet. If you must reach it remotely, put a **TLS
  reverse proxy** in front and/or a VPN; bind the container port to `127.0.0.1` behind that proxy.
- Only add extension repositories you trust. Installing an extension means running its code.

## Reporting

This is a personal project. If you find a security issue, please open an issue describing it (avoid
including anything sensitive) rather than exploiting it against shared infrastructure.
