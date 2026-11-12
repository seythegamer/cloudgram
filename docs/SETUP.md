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

## Troubleshooting

| Message | Meaning |
|---|---|
| `That does not look like a bot token` | The value is not in `botId:secret` form. Copy it again from BotFather. |
| `Telegram refused this token` | The token was revoked or mistyped. Use `/token` or `/revoke` in BotFather to get a new one. |
| `No Start message yet` | Press **Start** in the bot chat first, then click **I pressed Start** again. |
| `Telegram is rate limiting requests` | Wait the shown number of seconds and retry. |
| `Cannot reach Telegram` | No internet, a proxy, or `api.telegram.org` is blocked. |

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

The mod only reads `getUpdates` to find the `/start` message during setup. It does not read or
store other messages, and it has no commands. In the other direction it sends backup uploads and
status messages to the bound chat.
