# Cloudgram

Back up your singleplayer Minecraft worlds to **your own Telegram bot chat**.

- No account, no server, no relay — the mod talks straight to `api.telegram.org` with a bot token you create.
- Snapshots are designed to be encrypted before upload.
- Telegram is treated as a transport, not as the only copy: the local save always stays on disk.

> Status: the setup wizard works: paste a BotFather token, bind a private chat, and the config is
> remembered. Leaving a world force-saves it and packs it into the local vault
> (`run/cloudgram/vault` during development). Upload, encryption and restore are next.

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
$env:JAVA_TOOL_OPTIONS = '-Dcloudgram.selftest.leaveAfterTicks=120'
.\gradlew.bat runClient --args="--quickPlaySingleplayer <levelId>"
```

The hook is inert outside `fabric.development=true` and does nothing unless that property is set.
The resulting snapshot lands in `run/cloudgram/vault/<worldId[0..8]>/<snapshotId>/`.

The wizard can be opened directly (the normal way is the **Cloudgram** button on the title screen):

```powershell
$env:JAVA_TOOL_OPTIONS = '-Dcloudgram.selftest.openSetup=true'
.\gradlew.bat runClient
```

## Connecting your bot

See [`docs/SETUP.md`](docs/SETUP.md) for the BotFather steps, the binding flow, troubleshooting
and where the token is stored. The short version:

1. Create a bot with [@BotFather](https://t.me/BotFather), copy its token.
2. Title screen → **Cloudgram** → paste → **Verify token**.
3. **Open bot chat** → press **Start** → **I pressed Start**.
4. The status line shows **Connected to …**; the bot and chat are remembered in
   `config/cloudgram.json`.

## Snapshot format

The pack, manifest and splitting rules are defined in [`docs/SPEC.md`](docs/SPEC.md). That file is
the contract between the snapshot engine, the upload queue and the restore path; the
`dev.cloudgram.core` package is the implementation and is covered by unit tests.

## How it is meant to work

1. Create a bot with [@BotFather](https://t.me/BotFather) and paste the token into the mod. ✅
2. Press **Start** in the bot chat; the mod binds to that chat automatically. ✅
3. Quit a world → the world is force-saved, packed, encrypted and uploaded. ⏳ local packing works
4. On another PC, the pinned catalog lists your snapshots; restore as a copy, never silently over an existing world. ⏳

Currently implemented: the setup wizard and the local half of step 3 (force-save, pack,
manifest). Upload, encryption and restore are the next milestones.

## Security notes

- The bot token is stored in plain text in `config/cloudgram.json`. On POSIX systems the file is
  created with owner-only permissions; on Windows it relies on the user profile ACLs. DPAPI
  encryption is planned.
- The mod never sends the token anywhere except `api.telegram.org`, and never logs it.
- Leaked tokens are revoked in BotFather (**Revoke current token**), not by editing files.

## Licence

TBD.
