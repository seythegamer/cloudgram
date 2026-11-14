# AGENTS.md

Instructions for AI coding agents working in this repository. Read this first, then
[`README.md`](README.md), [`docs/SPEC.md`](docs/SPEC.md) and [`docs/SETUP.md`](docs/SETUP.md)
before changing behaviour.

## What this project is

Cloudgram is a **client-side Fabric mod for Minecraft Java 26.3** that backs up singleplayer
worlds to the user's own private Telegram bot chat. It has no backend: the mod talks directly to
`https://api.telegram.org`.

The local world is always the primary copy. A snapshot is packed locally, encrypted, uploaded as
Telegram documents, and indexed by a pinned encrypted catalog. Restoring downloads, decrypts,
verifies and extracts a snapshot as a **new save**; it never overwrites an existing world.

Status: setup, local snapshots, encrypted upload with resume, pinned catalog, and restore for both
encrypted and legacy plaintext snapshots are implemented. There is no server component.

## Non-negotiable invariants

Treat these as contracts. If a change requires breaking one, update `docs/SPEC.md`, keep the old
reader working, and add tests for both shapes.

1. **A snapshot exists only once its `manifest.json` is in the chat.** The manifest is uploaded
   last. An interrupted upload leaves orphan parts, which are harmless.
2. **Telegram is append-only and permanent.** Never add code that deletes a snapshot, part, index
   or catalog entry from the chat. Only the superseded *catalog message* may be deleted after the
   new catalog is pinned.
3. **Encryption is mandatory for every uploaded object.** There is deliberately no plaintext
   upload path. If the key is missing or unreadable, skip the upload and keep the local vault copy.
4. **The encryption key is independent of the bot token.** Never derive one from the other.
   Telegram sees the token on every request.
5. **Restore never overwrites a save.** Build in `.cloudgram-restore-*`, verify pack/part hashes,
   sizes and file count, then move to a free name. Delete the temp directory on any failure.
6. **The pinned catalog is the only discovery channel.** The Bot API has no history endpoint.
   Never unpin or delete the catalog, and never overwrite an unrelated pinned message.
7. **Never prune local snapshots that are not proven uploaded.** Only a directory with a readable
   `manifest.json` *and* `upload.json` is deletable. Pending/incomplete snapshots are never pruned.
8. **Pack bytes must be deterministic.** Same input bytes must produce the same ZIP and SHA-256 on
   every OS/JDK, because hashes are stored in the manifest and used for verification.
9. **Treat every downloaded byte and entry name as untrusted.** Verify hashes/sizes/AAD, reject
   `..` and duplicate entries, keep every resolved path inside the target root, and never trust
   manifest metadata blindly. Absolute entry names are normalized into the root rather than
   honoured as absolute paths.
10. **Never log, print, copy into a prompt, or commit the bot token or recovery key.** Also never
    include `config/cloudgram.json`, `run/config/cloudgram.json` or vault contents in bug reports.

## Build, test and run

Requires **JDK 25**. The Gradle wrapper pins Gradle 9.8.2; do not install or assume a system
Gradle. On Windows PowerShell the usual flow is:

```powershell
$env:JAVA_HOME = '<path-to-jdk-25>'

.\gradlew.bat test          # pure-Java tests; no Minecraft needed
.\gradlew.bat build         # compile + build/libs/cloudgram-<version>.jar
.\gradlew.bat runClient     # dev client; game dir is run/
```

Useful development hooks (only active when `fabric.development=true`, which `runClient` sets):

```powershell
.\gradlew.bat runClient '-Pcloudgram.selftest.leaveAfterTicks=120' --args="--quickPlaySingleplayer <levelId>"
.\gradlew.bat runClient '-Pcloudgram.selftest.openSetup=true'
.\gradlew.bat runClient '-Pcloudgram.selftest.openRestore=true'
.\gradlew.bat runClient '-Pcloudgram.selftest.openRestore=true' '-Pcloudgram.selftest.restoreFirst=true'
```

`run/` is a real game directory. `run/config/cloudgram.json` may contain a **real bot token and
recovery key**, and a development run can upload to the bound chat. Do not read or echo that file;
delete/rename it if a run must stay offline. `run-client*.log`, `run/`, `build/`, `.gradle/` are
ignored by git.

## Repository map

```
build.gradle                    Loom/Fabric build; Java release 25; self-test property forwarding
gradle.properties               Minecraft 26.3, Fabric Loader 0.20.1, Fabric API, mod version
src/main/java/dev/cloudgram/
  Cloudgram.java                Mod init: lifecycle events, executors, shutdown hook, upload pump
  core/                         Pure Java (no Minecraft imports); the durable format and logic
    SnapshotService.java        Scan/plan/pack/manifest into the local vault
    PackPlanner / PackWriter    Grouping and deterministic STORED ZIP writer
    PackSplitter / PackPart     Byte-range parts <= 19 MiB plus per-part hashes
    SnapshotManifest            cloudgram/snapshot/1 JSON contract
    ObjectNames, WorldPaths     Shared naming and safe relative-path rules
    WorldIdentity               Stable UUID in <world>/.cloudgram/world.json
    crypto/                     AES-256-GCM payload format, key, AAD bindings
    telegram/                   Bot API abstraction + HttpClient implementation
    upload/                     Uploader, upload.json, pending resume, vault pruning
    catalog/                    Pinned catalog, snapshot index, local mirror
    restore/                    Catalog resolution, download/verify/extract, save names
    config/                     cloudgram.json read/write and config record
    setup/                      BotFather token verification and /start binding
src/client/java/dev/cloudgram/client/
  CloudgramClient.java          Client entrypoint; title-screen button and self-test hooks
  setup/CloudgramSetupScreen    First-run wizard
  restore/CloudgramRestoreScreen, CloudgramImportPromptScreen
src/main/resources/fabric.mod.json
docs/SPEC.md                    Normative snapshot/catalog/restore format contract
docs/SETUP.md                   User-facing bot setup and troubleshooting
src/test/java/dev/cloudgram/core/**   JUnit 5 tests, pure JVM
```

## Runtime flow

### Exit/backup path

1. `Cloudgram.onInitialize` creates the encryption key if absent, installs a 120 s shutdown hook,
   and starts resuming pending uploads.
2. `SERVER_STARTED` ensures the world identity exists.
3. `SERVER_STOPPING` calls `server.saveEverything(false, true, true)` (flush to disk).
4. `SERVER_STOPPED` submits a `SnapshotRequest` to a **single-thread daemon executor**
   (`cloudgram-snapshot`, low priority).
5. `SnapshotService.capture` plans groups, writes deterministic packs, writes `manifest.json`
   last, and returns a `SnapshotResult`.
6. A second **single-thread executor** (`cloudgram-upload`) uploads it, publishes the catalog
   index/catalog, then prunes old local snapshots.

Packing and uploading are separate so a slow network never delays the next world's backup, while
uploads and catalog publishes stay serialized. Do not call blocking network/IO work on the
client/render or server thread.

### Upload path (`SnapshotUploader`)

For each pack sorted by id, for each part in index order:
read part → encrypt with `Aad.part(...)` → `sendDocument` → pace 1.2 s.
Then encrypt and upload the manifest (`Aad.manifest(...)`), pace, then send the summary text with
`CG1-<manifest message id>`. Write `upload.json` only after all of that. `upload.json` is also the
"done" marker for resume: if writing it fails after a successful upload, the snapshot is retried
next launch and duplicate messages are acceptable.

Retries: only code `0` (network), `429` and `5xx`; `429` honours `retry_after`; backoff is
exponential and capped at 30 s. All sleeps go through the injectable `Sleeper` so tests are fast.

### Catalog path (`CatalogService`)

After a successful snapshot upload:
1. upload an immutable encrypted **snapshot index** (`cloudgram/index/1`, AAD
   `index | worldId | snapshotId`) containing the full manifest and every part's file id;
2. fetch the pinned catalog (`getChat` → `pinned_message`), using the local mirror only when the
   fetched catalog has no entries at all;
3. add/replace the small summary entry, upload the encrypted catalog (AAD `catalog | chatId`),
   pin it, and best-effort delete the previous catalog message.

Catalog failure must never fail the snapshot. The next upload repairs it. A pinned text message or
a pinned document not named `cloudgram_catalog.json...` means "no catalog"; never overwrite it.

### Restore path (`RestoreService`)

1. Fetch and decrypt the pinned catalog.
2. If the entry is indexed, download/decrypt its index; legacy entries already embed the manifest
   and part ids.
3. Create a temp dir under `saves/`.
4. Download each part, decrypt when the entry is encrypted, verify part size/SHA-256, concatenate,
   verify pack size/SHA-256, then extract safely.
5. Require total extracted files to equal `manifest.stats.files` and the world identity to match.
6. Move to a unique save name (`SaveNames.unique`). On failure delete the temp dir.

Downloads retry like uploads and pause 350 ms between parts. `HttpTelegramTransport` caps a
download at 25 MiB; parts are 19 MiB precisely because Telegram's `getFile` limit is 20 MB.

## On-disk and wire formats

Every persisted JSON document carries a `format` tag and is read back through a strict parser that
rejects unknown formats. Local writes are atomic (`temp` + `ATOMIC_MOVE`, with a non-atomic
fallback). Do not add a new JSON format without a `format` constant, validation and round-trip tests.

| Format tag | Where | Code |
|---|---|---|
| `cloudgram/config/1` | `config/cloudgram.json` | `CloudgramConfig`, `ConfigStore` |
| `cloudgram/world/1` | `<world>/.cloudgram/world.json` and `meta` pack | `WorldIdentity` |
| `cloudgram/snapshot/1` | vault `manifest.json`, Telegram document | `SnapshotManifest` |
| `cloudgram/upload/1` | vault `upload.json` (local only) | `UploadRecord` |
| `cloudgram/index/1` | Telegram `cloudgram_index_<snapshotId>.json` | `SnapshotIndex` |
| `cloudgram/catalog/1` | Telegram `cloudgram_catalog.json` | `Catalog` |
| `cloudgram/catalog-mirror/1` | `<gameDir>/cloudgram/catalog.json` | `CatalogMirror` |
| `aes-256-gcm/v1` | object payload header `CG1E` | `PayloadCipher` |

Key constants and limits live in records: `PackOptions` (16 MiB target pack, 19 MiB parts, 30k
entries), `UploadOptions` (4 attempts, 1 s base backoff, 1.2 s pacing), `RestoreOptions` (4
attempts, 1 s base backoff, 350 ms pacing). Reuse them instead of hard-coding new values.

### Configuration compatibility

`CloudgramConfig` is a record with overloaded constructors so files written before newer fields
remain readable. Unknown JSON fields are ignored, but `format` is checked. When adding a field,
add a backwards-compatible constructor and a `withX` copy method; keep the existing value in every
copy. `withBot` intentionally drops the chat binding when the token changes (a chat id belongs to
exactly one bot) but keeps the encryption key and backup preference.

## Code conventions

- Indent with **tabs** (the existing Java files use tabs, not spaces). Keep line length roughly
  under 110 characters.
- Utility classes are `public final` with a private constructor. Data carriers are records with
  compact constructors that validate arguments and defensively copy mutable collections.
- Validation style: `throw new IllegalArgumentException("x is required")`, `"must be positive"`,
  `"must not be negative"`; domain failures use checked exceptions (`IOException`,
  `TelegramException`, `UploadException`, `RestoreException`, `CryptoException`).
- Keep `dev.cloudgram.core` free of Minecraft imports. It may use Gson (declared `compileOnly`)
  and the JDK only. Minecraft/Fabric code belongs in `Cloudgram`, the `client` source set, or
  `fabric.mod.json` wiring.
- Prefer extending an existing service and its options over adding a new framework or dependency.
  There is no DI container, ORM, logging abstraction beyond SLF4J, or mocking library.
- All JSON goes through `Json.gson()` (pretty-printed, HTML escaping disabled). All user-visible
  timing uses UTC epoch seconds/millis as already defined.
- Log through `Cloudgram.LOG`. Log actionable context, never secrets. Use `BotTokens.redact` if a
  token must appear in a diagnostic.
- Threads must be daemon threads and named (`cloudgram-*`); preserve the existing single-thread
  executors. Screens have their own daemon IO executor. Marshal UI updates back with
  `minecraft.execute`/`onClient` and check the `closed` flag.
- GUI code targets Minecraft 26.3: screens override `extractRenderState(GuiGraphicsExtractor, ...)`,
  use `graphics.text`, `graphics.centeredText`, `graphics.textWithWordWrap`, and
  `minecraft.gui.setScreen(...)`. Do not reintroduce older `render(...)`/`DrawContext` patterns.
- Keep comments focused on *why* (especially format/security/ordering constraints), matching the
  existing Javadoc style.

## Testing conventions

- JUnit Jupiter 5.11.4. Test classes are package-private; test methods are descriptive sentences.
- No Mockito/WireMock and no network. Hand-written fakes implement `TelegramTransport` (often
  throwing `UnsupportedOperationException` for calls a test does not use). See
  `SnapshotUploaderTest`, `RestoreServiceTest`, `CatalogServiceTest`, `TelegramSetupTest`.
- Use `@TempDir` for filesystem tests; build synthetic worlds with `TestWorlds` where possible.
- Inject `Sleeper` (and `Clock` in setup) instead of sleeping; assert the recorded sleep values when
  retry/pacing behaviour matters.
- Tests are pure JVM; `.\gradlew.bat test` must pass without launching Minecraft or touching
  `run/`. Never write to a real config, vault or save in a test.
- Add tests for: new format fields, legacy-read compatibility, path traversal, hash/size/AAD
  failures, retry classification, pruning safety, and "never overwrite" restore semantics.

## Security rules

- The **local vault is plaintext** (packs are encrypted only in transit for upload). Do not claim
  otherwise in docs or UI.
- The bot token and recovery key live in plaintext in `config/cloudgram.json`. On POSIX the file is
  chmod `600`; Windows relies on profile ACLs. DPAPI is planned, not implemented.
- The mod may contact only `api.telegram.org`. Do not add analytics, telemetry, update checks,
  crash reporting, or third-party hosts.
- The upload path must keep using fresh random 96-bit nonces and AAD that binds
  world/snapshot/pack/part. Changing an AAD string is a **breaking wire-format change**; old
  backups must remain decryptable.
- Never trust catalog/index/manifest content: verify it against the manifest, the requested
  snapshot and the hashes before using it. `PackExtractor` and `WorldPaths.resolve` already
  contain unsafe entries; keep it that way.
- Do not put the token in a URL query string, log line, exception message, file name, test fixture
  or commit.

## Known gaps and traps

These are real limitations; do not assume the README's aspirational wording is implemented.

- `SnapshotManifest.prev` exists in the schema but `SnapshotService` always writes `null`. There is
  no snapshot chain today.
- Packs are content-addressed and hashed deterministically, but uploads are **not deduplicated or
  incremental**: every part of every snapshot is uploaded again. Do not assume a "changed packs
  only" path exists.
- A restore code (`CG1-<id>`) identifies a message but cannot be resolved without the catalog.
  There is no Bot API message lookup.
- The pinned catalog is the only discovery channel. If it is deleted and the local mirror is
  absent, older snapshots are still in the chat but not discoverable from that device.
- `Catalog`'s class Javadoc still says it "always contains the full manifest"; indexed entries
  actually keep only a summary and point at `SnapshotIndex`. The code is authoritative.
- `HttpTelegramTransport` buffers an entire encrypted part and the multipart body in memory.
  Raising the part size raises peak heap; keep the 19 MiB / 20 MB relationship and the 25 MiB
  download guard in mind.
- The upload record is the local "done" marker. A missing/failed `upload.json` intentionally
  causes a re-upload on the next launch; duplicates are expected and harmless.
- There is no CI configuration, formatter, or linter. Match the surrounding style manually and run
  the tests.
- The codebase targets Minecraft 26.3 with Loom 1.19-SNAPSHOT and Java 25. If dependency
  resolution fails, check `gradle.properties`/`build.gradle` and the README rather than "fixing"
  versions to older releases.

## Definition of done for a change

- [ ] `.\gradlew.bat test` passes (or the final message explains exactly why it could not run).
- [ ] `.\gradlew.bat build` passes for anything shipped; the jar still contains `LICENSE`.
- [ ] Format/behaviour changes update `docs/SPEC.md`, and user-visible changes update `README.md`
      and/or `docs/SETUP.md`.
- [ ] Old configs, manifests, catalogs and plaintext legacy snapshots still read/restore.
- [ ] Tests cover the failure path, not only the happy path, with no network and no real user data.
- [ ] No token, key, vault content, `run/` artifact or generated build output is added to the diff.
- [ ] If a screen changed, say which self-test hook/manual flow was used.
