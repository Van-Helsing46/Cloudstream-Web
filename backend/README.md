# Cloudstream Web backend (Kotlin + Ktor)

Extension runtime, REST API and streaming proxy.

## Requirements
- **JDK 21+**

## Run
```bash
# generate the wrapper the first time (requires a local Gradle or IntelliJ IDEA)
gradle wrapper
./gradlew run         # http://localhost:8080
```

## Main endpoints
| Method | Path | Description |
|---|---|---|
| GET | `/health` | Liveness |
| GET | `/api/v1/providers` | Active providers |
| GET | `/api/v1/search?q=...&provider=` | Aggregated search |
| GET | `/api/v1/providers/{id}/home?page=` | Provider main page |
| GET | `/api/v1/providers/{id}/detail?id=` | Content detail |
| GET | `/api/v1/providers/{id}/links?id=` | Streaming links |
| GET | `/api/v1/stream?url=&headers=` | Streaming proxy |
| *   | `/api/v1/extensions/...` | Extension management |
| *   | `/api/v1/profiles`, `/api/v1/library/...` | Profiles and per-profile library |

Full contract: OpenAPI at `/swagger`.

## Layout
```
com.cloudstreamweb
├── Application.kt          # Ktor entrypoint
├── config/AppConfig.kt     # configuration from env
├── domain/Models.kt        # data contract (FE ⇄ BE)
├── provider/               # Provider interface + registry
├── extensions/             # extension runtime + ExtensionManager (see its README)
├── library/                # profiles, watchlist, history/resume
├── proxy/                  # streaming proxy
└── plugins/                # Ktor configuration (routing, auth, serialization, http)
```

See `src/main/kotlin/com/cloudstreamweb/extensions/README.md` for how extensions run on the JVM.
