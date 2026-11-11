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

Until the upload queue exists, snapshots are written to a local vault:

```
<gameDir>/cloudgram/vault/<worldId[0..8]>/<snapshotId>/
    manifest.json
    packs/<safePackId>.cgp
```

The manifest is written last; a directory without one is an incomplete run and is deleted by the
producer. `safePackId` follows the rule in §5.

## 7. What this format deliberately does not do

- **No merge.** Snapshots are immutable; restoring is an explicit user choice.
- **No outer compression.** See §4.3.
- **No encryption here.** Packs are encrypted *after* being built, as a transport transform;
  hashes in the manifest stay computed over plaintext packs so deduplication still works.
- **No deletion semantics.** Retention/GC is a separate layer that reference-counts packs across
  retained manifests.
