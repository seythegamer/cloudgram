# Connecting Cloudgram to your Telegram bot

Cloudgram talks directly to Telegram with a bot you create. There is no Cloudgram account and no
server in between: the mod and `api.telegram.org` are the only two parties.

## 1. Create the bot

1. Open [@BotFather](https://t.me/BotFather) in Telegram (the **Help** button in the wizard opens
   the same chat).
2. Send `/newbot` and follow the two prompts: a display name and a username ending in `bot`.
3. BotFather replies with a token that looks like `123456789:AA...`. That token is the bot's
   password — anyone who has it can send messages as your bot.

## 2. Paste the token in Minecraft

1. On the title screen, click **Cloudgram** (top right).
2. Paste the token and click **Verify token**.

The mod calls `getMe` to confirm the token, saves it, and shows the bot's `@username`.

## 3. Bind the chat

1. Click **Open bot chat** and press **Start** in Telegram.
2. Return to Minecraft and click **I pressed Start**.

The mod binds to the private chat that sent the newest `/start` after you verified the token. It
then sends (and tries to pin) a short confirmation message. Older messages can never be mistaken
for a fresh binding because the wizard records the update cursor before asking you to press Start.

The status line should read **Connected to …**. Click **Done**; the wizard will remember the bot
and chat on the next launch.

## 4. Save your recovery key

The first launch creates a random 256-bit backup key. Uploaded packs and manifests are encrypted
with it, so the Telegram copies cannot be read without it.

1. Open **Cloudgram** again while the chat is bound.
2. Click **Copy recovery key** and store the copied value somewhere safe (a password manager).
3. Repeat on every device where you might want to restore. The same key opens the same backups.

The key is stored in `config/cloudgram.json` and is never displayed in the wizard. Keep the file
private; losing the key means losing access to the encrypted Telegram copies.

## 5. Restore a backup

Every successful upload updates a **pinned catalog** in the chat. Any device with the bot token and
the recovery key can read it:

1. Title screen → **Cloudgram** → **Restore from Telegram...**.
2. Pick a snapshot from the list. The screen shows the world name, the time, the file count and the
   `CG1-…` restore code.
3. Click **Restore**. The download is decrypted and verified part by part, then the world appears in
   the world list under a free name.

Cloudgram never overwrites an existing save. If a restore fails (wrong key, interrupted download,
changed bytes), the `saves` directory is left untouched and the temporary directory is removed.

### On a new PC

Install the mod, connect a bot (steps 1–3), then replace the freshly generated key with the one from
your other PC:

1. The first time the title screen appears, Cloudgram checks the chat in the background. If the
   chat already holds snapshots it offers **Restore now** / **Not now** (shown once per install).
2. Open **Cloudgram** → **Restore from Telegram...** (or choose **Restore now** in that offer).
3. Click **Change key...**, paste the recovery key and click **Save key**.
4. The snapshot list appears. Restore as usual.

If you chose **Not now**, nothing is lost: the restore list stays available from the Cloudgram
button, and declining only silences the automatic offer.

The same bot can be used on several PCs; each keeps its own `config/cloudgram.json`.

## Troubleshooting

| Message | Meaning |
|---|---|
| `That does not look like a bot token` | The value is not in `botId:secret` form. Copy it again from BotFather. |
| `Telegram refused this token` | The token was revoked or mistyped. Use `/token` or `/revoke` in BotFather to get a new one. |
| `No Start message yet` | Press **Start** in the bot chat first, then click **I pressed Start** again. |
| `Telegram is rate limiting requests` | Wait the shown number of seconds and retry. |
| `Cannot reach Telegram` | No internet, a proxy, or `api.telegram.org` is blocked. |
| `The catalog cannot be decrypted with this key` | This PC holds a different recovery key. Use **Change key...** and paste the key from the PC that made the backups. |
| `... failed its SHA-256 check` | The uploaded bytes do not match the manifest (a damaged upload). Try another snapshot; the local vault copy is unaffected. |
| `No snapshots yet` | The chat has no Cloudgram catalog. Leave a world on any device with the same bot first. |

## Where the token and key are stored

The token lives in `config/cloudgram.json` in the Minecraft game directory
(`run/config/cloudgram.json` in a development client). It is stored in **plain text**:

- On Linux and macOS the mod creates the file with owner-only permissions (`rw-------`).
- On Windows the file inherits the normal user profile ACLs. Windows DPAPI encryption is planned
  as a hardening step.

The same file also holds the base64 **recovery key** used for encryption. Do not share that file,
and do not include it in a bug report. If the token leaks, open BotFather, pick the bot, and use
**Revoke current token**; the old token stops working immediately and you can paste the new one into
the wizard. Rotating the bot token does not change the backup key.

## What the bot can see

The mod only calls `getUpdates` to find the `/start` message during setup. It uses `getChat` to read
the pinned catalog, `getFile` and the file endpoint to download snapshot parts, and
`pinChatMessage`/`deleteMessage` to keep exactly one catalog pinned. It does not read or store other
messages, and it has no commands. In the other direction it sends backup uploads and status messages
to the bound chat.
