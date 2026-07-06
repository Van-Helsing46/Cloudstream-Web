# Cloudstream extension runtime on the JVM

## How it works

A `.cs3` is **Android DEX bytecode**: a standard JVM cannot load it. The working approach is
to **recompile the extension's source** against the Cloudstream JVM library
(`com.github.recloudstream.cloudstream:library-jvm:master-SNAPSHOT`, JitPack) and instantiate
the provider as a plain JVM class.

| File | Role |
|---|---|
| `ExtensionRuntime.kt` | Runtime contract + `BundledExtensionRuntime` (factory catalog of the recompiled extensions) |
| `MainApiProviderAdapter.kt` | Adapter `MainAPI` (Cloudstream) → `Provider`/`domain/Models.kt` |
| `ExtensionManager.kt` | Repos/install/update/uninstall + persistence + activation |
| `RepositoryModels.kt` | `repo.json`/`plugins.json` format + persisted state + API DTOs |
| `bundled/` | Sources of the recompiled extensions (**empty in the public repository**: no extension ships with the project) |

The `.cs3` downloaded by the `ExtensionManager` is archived as an artifact/metadata
(versioning, hash); **execution always goes through the recompiled version** present in the
runtime.

## Adding a bundled extension

1. Copy the provider's Kotlin source into `bundled/` (keep the original package).
2. Stub any Android dependencies under `src/main/java/android/...`
   (`android.util.Log`, already provided, is often enough; WebView/Cloudflare would need a
   headless browser).
3. Register the factory in `BundledExtensionRuntime.factories` with the `internalName`
   from the repository manifest (this replaces the Android `Plugin.load(Context)`:
   configuration that came from SharedPreferences becomes a constructor parameter).
4. The extension becomes installable/activatable via `/api/v1/extensions` (`runtimeSupported: true`).

## Open question

Scaling the executable catalog: a curated bundle of extensions vs on-demand source
compilation vs DEX→JAR conversion (never attempted). Per-extension classloader isolation
becomes relevant only once non-bundled bytecode gets loaded.
