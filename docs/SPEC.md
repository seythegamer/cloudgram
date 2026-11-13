# Cloudgram snapshot format

Status: **v1 draft** — this document is the contract between the snapshot engine, the
upload queue and the restore/import path. Anything that changes here changes all three.

## 1. Goals

- A snapshot is **append-only** and self-contained: a manifest plus a set of immutable packs.
- Packs are **content-addressed**: the same bytes always produce the same hash, on any machine,
  on any JDK. This is what makes incremental uploads and deduplication work.
- Nothing in this format depends on Minecraft classes. It is produced by `dev.cloudgram.core`.

## 2. World identity

Every world carries a stable identity that survives renames and copies:

```
<saves>/<world>/.cloudgram/world.json
```

```json
{
  "format": "cloudgram/world/1",
  "id": "0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f",
  "createdAt": 1762893012,
  "origin": "local"
}
```

- `id` is a random UUID generated the first time the mod sees the world.
- If the file is missing (world created before the mod, or imported from elsewhere), it is
  generated on first backup. The world folder is never identified by its name.
- The file is written atomically and is included in the `meta` pack, so a restore preserves identity.

## 3. What gets packed

The world directory is scanned recursively. Every regular file is assigned to exactly one group.

| Group key | Contents |
|---|---|
| `cell:<x>:<z>` | `region/r.X.Z.mca`, `entities/r.X.Z.mca`, `poi/r.X.Z.mca` in **all** dimensions, grouped by region coordinate |
| `meta` | `level.dat`, `level.dat_old`, `icon.png`, `.cloudgram/**`, `data/**`, `playerdata/**`, `players/**`, `stats/**`, `advancements/**`, `datapacks/**`, and any per-dimension `dimensions/<ns>/<path>/data/**` |
| `misc` | anything else (e.g. `forcedchunks.dat`, unknown mod files) |

Since Minecraft 26.x, the overworld lives under `dimensions/minecraft/overworld/` and region files
are `dimensions/<namespace>/<path>/region/r.X.Z.mca`; top-level `region/` from older worlds is
still recognised.

Grouping by region coordinate keeps the layout of a single play session together: exploring one
area rewrites a handful of cells, so only those packs change between snapshots.

Excluded by default (never packed):

- `session.lock`
- `logs/**`, `crash-reports/**`, `debug/**`
- `.cloudgram/tmp/**` (in-progress pack files)
- `.DS_Store`

## 4. Pack = deterministic ZIP

A pack is a ZIP archive written with these rules:

1. Entries sorted by path, ascending, using `/` separators.
2. Entry names are relative to the world root.
3. Every entry is **STORED** (no compression).
   Anvil region files are already zlib-compressed internally; recompressing them costs CPU and
   gains almost nothing. More importantly, fixed compression settings keep pack hashes stable
   across JDK/zlib versions.
4. Every entry has the fixed timestamp `1980-01-01T00:00`.
5. No directory entries, no extra fields, no comments.
6. File contents are written byte-for-byte.

Result: identical input bytes → identical pack bytes → identical SHA-256, on every machine.

### Sizing and splitting

- Target pack size: **16 MiB** (`PackOptions.targetPackBytes`).
- A group larger than the target is split at **file boundaries** into `meta`, `meta~2`, `meta~3`, …
  (same for `cell:x:z~2`). A pack never holds more than **30,000 entries** (ZIP without Zip64
  allows 65,535; staying lower keeps the central directory small).
- A single file larger than a part limit stays in its own pack; the pack is then split into
  **byte-range parts** (below). ZIP streams can be cut at arbitrary offsets.

## 5. Parts

Telegram's Bot API cannot download files larger than 20 MB, so every pack is split into parts of at
most **19 MiB** (`19 * 1024 * 1024 = 19,922,944` bytes), safely under the limit.

```json
{ "index": 0, "offset": 0, "size": 19922944, "sha256": "…" }
```

- Parts are raw byte ranges of the pack, concatenated in `index` order to reconstruct it.
- Every part has its own SHA-256, verified after download.
- A pack smaller than 19 MiB has exactly one part.

### Object naming on Telegram

```
cg1_<worldId[0..8]>_<safePackId>_<packSha256[0..12]>_p<partIndex, 3 digits>.bin
```

`safePackId` replaces `:` and `~` with `_` and `-` with `n`, e.g. `cell:-1:2` → `cell_n1_2`.

## 6. Manifest

One manifest per snapshot, uploaded **after** all of its packs (a snapshot without a manifest does
not exist):

```json
{
  "format": "cloudgram/snapshot/1",
  "world": {
    "id": "0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f",
    "name": "Survival",
    "mcVersion": "26.3",
    "dataVersion": 4567
  },
  "snapshot": {
    "id": "20261111T230000Z-a1b2c3",
    "createdAt": 1762898400,
    "device": "DESKTOP-SEY",
    "mod": "0.1.0"
  },
  "packs": [
    {
      "id": "cell:0:0",
      "sha256": "e3b0c442…",
      "size": 8123456,
      "parts": [
        { "index": 0, "offset": 0, "size": 8123456, "sha256": "9f2c…" }
      ]
    }
  ],
  "stats": { "files": 8123, "rawBytes": 12884901888, "packBytes": 9663676416 },
  "prev": "20261110T221000Z-9f8e"
}
```

- `snapshot.id` is UTC `yyyyMMdd'T'HHmmss'Z'` plus a short random suffix.
- `prev` points at the previous snapshot of the same world, or is absent for the first one.
- `stats.rawBytes` is the sum of source file sizes; `packBytes` the sum of pack sizes.
- Manifests are small (tens of KB) and are not split.

### Local staging (before upload)

Snapshots are written to a local vault before upload:

```
<gameDir>/cloudgram/vault/<worldId[0..8]>/<snapshotId>/
    manifest.json
    packs/<safePackId>.cgp
```

The manifest is written last; a directory without one is an incomplete run and is deleted by the
producer. `safePackId` follows the rule in §5.

### Upload order

The upload queue sends, in this order:

1. every part of every pack, sorted by pack id and then part index;
2. the manifest, renamed
   `cg1_<worldId[0..8]>_<snapshotId>_manifest.json` (parts keep the §5 name);
3. a summary text message containing `CG1-<manifest message id>`.

The manifest is the existence marker: an interrupted upload leaves only orphan parts, which restore
ignores. A retried request can produce a duplicate message; duplicates are harmless because a
snapshot is immutable and identified by its manifest.

### Encryption

Every pack part and the manifest is encrypted before `sendDocument`:

- scheme `aes-256-gcm/v1`: AES-256-GCM with a fresh random 12-byte nonce and a 16-byte tag;
- object layout `"CG1E" | version 1 | nonce | ciphertext | tag` (5 + 12 + n + 16 bytes);
- AAD binds each object to its place: `worldId`, `snapshotId`, `packId` and part index for parts,
  `worldId` and `snapshotId` for the manifest. A valid part cannot be replayed as another part,
  pack or snapshot even though every object shares one key;
- the 256-bit key is random, independent of the bot token, stored in `cloudgram.json`, and
  copyable from the setup screen as a recovery key;
- hashes in the manifest stay over the plaintext bytes, so deduplication is unchanged and a
  restore can verify after decryption;
- there is deliberately no plaintext upload path: if the key is missing or unreadable, the upload
  is skipped and the local vault copy stays complete.

An encrypted object is 33 bytes larger than its plaintext, so a 19 MiB part still fits under the
20 MB `getFile` download limit.

### Local upload record

`upload.json` is written next to the manifest after a successful upload. It is never uploaded and
never contains the bot token or the encryption key:

```json
{
  "format": "cloudgram/upload/1",
  "worldId": "0f3c9a2e-7b1d-4f5a-9c3e-2a1b4c5d6e7f",
  "snapshotId": "20261111T230000Z-a1b2c3",
  "chatId": 123456789,
  "manifest": { "messageId": 42, "fileId": "BQAC…", "fileName": "…_manifest.json", "fileSize": 8123 },
  "packs": [
    {
      "id": "cell:0:0",
      "sha256": "e3b0c442…",
      "parts": [
        { "messageId": 40, "fileId": "BQAC…", "fileName": "…_p000.bin", "fileSize": 8123456 }
      ]
    }
  ],
  "summaryMessageId": 43,
  "restoreCode": "CG1-42",
  "encryption": "aes-256-gcm/v1",
  "keyFingerprint": "9c1f00a7b3d24e88"
}
```

`summaryMessageId` is `0` when the summary could not be sent; the snapshot is still complete because
the manifest is already in the chat. `encryption` and `keyFingerprint` are absent in records written
before encryption existed; those snapshots are plaintext and must be handled as such by restore.

### Resuming an interrupted upload

The vault is the durable half of a backup, so `upload.json` doubles as a "done" marker. On startup
Cloudgram scans `vault/<world>/<snapshot>/` and uploads every directory that has a `manifest.json`
whose packs all exist at the recorded size and that has no `upload.json`. At most three pending
snapshots are retried per launch, oldest first, and the run stops at the first failure so an offline
PC does not hammer Telegram. Work goes through the same single-thread upload executor as live
uploads, which keeps catalog publishes serialized.

This matters most when the game is closed while still in a world: the shutdown hook below gives the
pack a bounded grace period (120 s), and the next launch uploads whatever was packed but not sent.

A JVM shutdown hook waits up to 120 s for an in-flight pack to finish, so closing the window from
inside a world no longer leaves that exit with no backup at all. If the pack exceeds the grace
period, the warning names the snapshot and the exit is lost; everything packed before it is intact.

## 7. Pinned catalog and snapshot index

The Bot API has no history endpoint, so a fresh device cannot list the files in a chat. Cloudgram
publishes one **pinned catalog document** per chat; `getChat` returns it as `pinned_message`, and it
is the only discovery channel. It holds a small summary per snapshot and points at an immutable
**snapshot index** document that carries the details:

```json
{
  "format": "cloudgram/catalog/1",
  "updatedAt": 1762900000000,
  "snapshots": [
    {
      "summary": {
        "worldId": "0f3c9a2e-…",
        "snapshotId": "20261111T230000Z-a1b2c3",
        "worldName": "Survival",
        "createdAt": 1762898400,
        "files": 8123,
        "packBytes": 9663676416
      },
      "restoreCode": "CG1-42",
      "manifestMessageId": 42,
      "manifestFileId": "BQAC…",
      "encryption": "aes-256-gcm/v1",
      "keyFingerprint": "9c1f00a7b3d24e88",
      "index": { "messageId": 43, "fileId": "BQAC…" }
    }
  ]
}
```

```json
{
  "format": "cloudgram/index/1",
  "manifest": { "format": "cloudgram/snapshot/1", "…": "the full manifest" },
  "packs": [
    { "id": "cell:0:0", "parts": [ { "index": 0, "messageId": 40, "fileId": "BQAC…" } ] }
  ]
}
```

- The catalog is encrypted with AAD `catalog | <chatId>` and uploaded as `cloudgram_catalog.json`.
  The index is encrypted with AAD `index | <worldId> | <snapshotId>` and uploaded once per snapshot
  as `cloudgram_index_<snapshotId>.json`.
- Keeping the manifest and part ids out of the catalog is what bounds its size: the pinned document
  grows by roughly 250 bytes per snapshot instead of by the whole manifest, so a chat can hold
  thousands of snapshots without approaching Telegram's 20 MB download limit.
- After each successful snapshot upload the mod uploads the index first, then fetches the current
  catalog, adds or replaces the entry for that world and snapshot, uploads the new document, pins
  it, and deletes the superseded catalog message. A failed index upload never produces an entry.
- Publishing is best-effort: a snapshot is complete once its manifest is in the chat, so a failed
  catalog update never invalidates a backup; the next upload repairs it.
- A pinned text message (the setup greeting) or a pinned document with another name means "no
  catalog yet"; Cloudgram never overwrites an unrelated pin.
- If the pin is lost, the next upload starts from a local mirror of the last published catalog
  (`<gameDir>/cloudgram/catalog.json`). The mirror stores the chat id, because file ids only exist
  in one chat, and it is only used when the chat has no catalog at all; a catalog that is present
  always wins. Index documents are never deleted, so the restored entries still resolve.
- Catalogs written before the index existed embed `manifest` and `packs` directly. Both shapes are
  read and restored through the same path; only new entries are indexed.
- `encryption` is absent for entries created from pre-encryption uploads; their parts are plaintext.

## 8. Restore

Restore never overwrites an existing world:

1. Fetch the pinned catalog and decrypt it with the installation's recovery key.
2. For an indexed entry, download and decrypt its snapshot index to learn the manifest and the part
   file ids. A legacy entry already carries both.
3. Build the world in a temporary directory below `saves/` (`.cloudgram-restore-*`).
4. For each pack, download every part (`getFile` + `/file/bot…`), decrypt when the catalog entry is
   encrypted, verify the part SHA-256 and size, concatenate, verify the pack SHA-256 and size, then
   extract into the temporary world directory. Pack entry names are untrusted: absolute paths,
   `..` and duplicate entries are rejected.
5. Verify that the total file count equals `manifest.stats.files`, and that
   `.cloudgram/world.json` carries the catalog's world id (write it if an old snapshot predates it).
6. Move the temporary world to a unique name (`Survival`, `Survival (Cloudgram 2)`, …) and delete
   the temporary directory on any failure.

Downloads retry rate limits, network errors and 5xx with backoff. The 20 MB `getFile` limit is why
parts are capped at 19 MiB.

## 9. What this format deliberately does not do

- **No merge.** Snapshots are immutable; restoring is an explicit user choice.
- **No outer compression.** See §4.3.
- **No encryption inside the pack format.** Packs and manifests are encrypted on the way to
  Telegram, as a transport transform; hashes in the manifest stay computed over plaintext packs so
  deduplication still works.
- **No deletion semantics.** Retention/GC is a separate layer that reference-counts packs across
  retained manifests.
