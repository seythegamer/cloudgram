# Cloudgram

Back up your singleplayer Minecraft worlds to **your own Telegram bot chat**.

- No account, no server, no relay — the mod talks straight to `api.telegram.org` with a bot token you create.
- Snapshot packs and manifests are encrypted with AES-256-GCM before upload; the key stays on your
  machine and is never derived from the bot token.
- Telegram is treated as a transport, not as the only copy: the local save always stays on disk.

> Status: setup, local snapshots, encrypted upload, recovery-key export and restore work. Leaving a
> world force-saves and packs it, then uploads every encrypted part, the encrypted manifest, and a
> summary with a `CG1-…` restore code. The pinned catalog lets any device with the same recovery key
> list and restore those snapshots as new saves. Backups made *before* encryption existed are
> plaintext; restore detects both formats.

## Requirements

- Minecraft **26.3** (Java Edition, Fabric)
- **JDK 25**
- Fabric Loader 0.20.1+, Fabric API 0.166.2+26.3

## Development

```powershell
$env:JAVA_HOME = '<path-to-jdk-25>'
.\gradlew.bat build        # compile + produce build/libs/cloudgram-<version>.jar
.\gradlew.bat test         # pure-Java core tests (no Minecraft needed)
.\gradlew.bat runClient    # launch a dev client with the mod loaded
```

The Gradle wrapper pins Gradle 9.8.2; no global Gradle install is needed.

For an automated end-to-end check, the client has a development-only hook that leaves the world
the same way the pause menu does:

```powershell
.\gradlew.bat runClient '-Pcloudgram.selftest.leaveAfterTicks=120' --args="--quickPlaySingleplayer <levelId>"
```

The hook is inert outside `fabric.development=true` and does nothing unless that property is set.
The resulting snapshot lands in `run/cloudgram/vault/<worldId[0..8]>/<snapshotId>/`.

> If `run/config/cloudgram.json` is bound to a real bot chat, a development snapshot is uploaded
> there. Delete or rename that file, or uncheck backups, if you only want local packing.

The wizard or the restore list can be opened directly (the normal way is the **Cloudgram** button on
the title screen):

```powershell
.\gradlew.bat runClient '-Pcloudgram.selftest.openSetup=true'
.\gradlew.bat runClient '-Pcloudgram.selftest.openRestore=true'
```

## Connecting your bot

See [`docs/SETUP.md`](docs/SETUP.md) for the BotFather steps, the binding flow, troubleshooting
and where the token is stored. The short version:

1. Create a bot with [@BotFather](https://t.me/BotFather), copy its token.
2. Title screen → **Cloudgram** → paste → **Verify token**.
3. **Open bot chat** → press **Start** → **I pressed Start**.
4. The status line shows **Connected to …**; the bot and chat are remembered in
   `config/cloudgram.json`. Use **Copy recovery key** to save the key you will need to restore on
   another PC.

## Snapshot format

The pack, manifest and splitting rules are defined in [`docs/SPEC.md`](docs/SPEC.md). That file is
the contract between the snapshot engine, the upload queue and the restore path; the
`dev.cloudgram.core` package is the implementation and is covered by unit tests.

## Uploads

After the local snapshot is complete, `SnapshotUploader` sends it to the bound private chat:

1. every part of every pack (sorted by pack id, then part index), named
   `cg1_<world>_<pack>_<sha>_pNNN.bin`;
2. `cg1_<world>_<snapshot>_manifest.json`, last — a snapshot exists only once its manifest is in
   the chat;
3. a short summary that contains the restore code `CG1-<manifest message id>`.

Uploads are paced one document every 1.2 s. Rate limits (`429 retry_after`), network errors and
Telegram 5xx responses are retried with backoff; a retry may produce a duplicate message, which is
harmless because snapshots are append-only. If the upload fails, the complete snapshot stays in
`<gameDir>/cloudgram/vault/` and the failure is logged.

Before each document is sent it is encrypted with AES-256-GCM (`aes-256-gcm/v1`, `CG1E` header,
fresh 12-byte nonce) and bound to its world, snapshot, pack and part index. The key is generated on
first launch, stored in `config/cloudgram.json`, and independent of the bot token. There is no
plaintext fallback: if the key cannot be read, the upload is skipped and the local copy remains.

Each uploaded snapshot also gets an `upload.json` next to its manifest, recording Telegram's
message ids, file ids, the restore code, the encryption scheme and a short key fingerprint. It is
never uploaded and never contains the bot token or the encryption key.

Because that file is only written on success, it is also the marker for an unfinished upload. On
startup Cloudgram retries up to three complete snapshots that have a manifest but no `upload.json`,
oldest first. This covers quitting the game while an upload is still running; a shutdown hook also
waits up to two minutes for a pack that is still being written, so closing the window from inside a
world does not lose that exit outright.

## Catalog and restore

The Bot API has no history endpoint, so the mod keeps one **pinned catalog** document in the chat.
After every successful upload it first stores an immutable per-snapshot **index** (the manifest plus
every part's `file_id`), then fetches the catalog, adds or replaces the snapshot summary, uploads a
new encrypted `cloudgram_catalog.json`, pins it, and deletes the superseded catalog message. Keeping
the manifest out of the catalog means the pinned document grows by a few hundred bytes per snapshot
instead of by megabytes, so discovery keeps working for a chat with thousands of backups. A failed
catalog update never invalidates the snapshot; the next upload repairs it.

On any device:

1. Title screen → **Cloudgram** → **Restore from Telegram...**.
2. The screen reads the pinned catalog and lists your snapshots (`CG1-…` restore codes included).
3. **Restore** downloads every part, decrypts and verifies it, checks the pack hashes and the file
   count, then moves the result to a free save name (`Survival (Cloudgram 2)`).
4. Nothing is ever overwritten: a failed restore leaves `saves/` untouched and deletes its temporary
   directory.

When Cloudgram is installed on a machine whose chat already holds snapshots, the title screen checks
the catalog once in the background and offers **Restore now** / **Not now**. Declining only silences
that offer; the restore list remains available.

A new PC needs the recovery key from the PC that created the backups. Open **Restore from
Telegram...** and use **Change key...** to paste it; uploads from the new PC then use the same key.

## How it is meant to work

1. Create a bot with [@BotFather](https://t.me/BotFather) and paste the token into the mod. ✅
2. Press **Start** in the bot chat; the mod binds to that chat automatically. ✅
3. Quit a world → the world is force-saved, packed, encrypted and uploaded. ✅
4. On another PC, the pinned catalog lists your snapshots; restore as a copy, never silently over an existing world. ✅

Currently implemented: setup, force-save, packing, encrypted upload with retries, recovery-key
export, the pinned catalog and restore-as-copy for both encrypted and legacy plaintext snapshots.

## Security notes

- The bot token is stored in plain text in `config/cloudgram.json`. On POSIX systems the file is
  created with owner-only permissions; on Windows it relies on the user profile ACLs. DPAPI
  encryption is planned.
- The backup key is stored in the same file as URL-safe base64. It is never uploaded, never
  logged, and never displayed by the setup screen; the wizard only copies it to the clipboard on
  request. Losing it means the Telegram copies cannot be decrypted.
- The mod never sends the token anywhere except `api.telegram.org`, and never logs it.
- Leaked tokens are revoked in BotFather (**Revoke current token**), not by editing files.

## Licence

TBD.
