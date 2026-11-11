# Cloudgram

Back up your singleplayer Minecraft worlds to **your own Telegram bot chat**.

- No account, no server, no relay — the mod talks straight to `api.telegram.org` with a bot token you create.
- Snapshots are encrypted before upload.
- Telegram is treated as a transport, not as the only copy: the local save always stays on disk.

> Status: early development. Nothing is uploaded yet; the mod currently only hooks the world lifecycle and logs what it sees.

## Requirements

- Minecraft **26.3** (Java Edition, Fabric)
- **JDK 25**
- Fabric Loader 0.20.1+, Fabric API 0.166.2+26.3

## Development

```powershell
$env:JAVA_HOME = '<path-to-jdk-25>'
.\gradlew.bat build        # compile + produce build/libs/cloudgram-<version>.jar
.\gradlew.bat runClient    # launch a dev client with the mod loaded
```

The Gradle wrapper pins Gradle 9.8.2; no global Gradle install is needed.

## How it is meant to work

1. Create a bot with [@BotFather](https://t.me/BotFather) and paste the token into the mod.
2. Press **Start** in the bot chat; the mod binds to that chat automatically.
3. Quit a world → the world is force-saved, packed, encrypted and uploaded.
4. On another PC, the pinned catalog lists your snapshots; restore as a copy, never silently over an existing world.

## Licence

TBD.
