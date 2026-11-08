# WearTubePlus — Project Plan

A fork of [`channelramble/WearTube`](https://github.com/channelramble/WearTube) (MIT), focused on
**offline downloads** first, distributed as **signed APKs on GitHub Releases**.

## Status (2026-11-08)

- [x] **M0** toolchain + `assembleDebug`
- [x] **M1** fork identity (`com.hallide.weartubeplus`, WearTubePlus, wrapper, portable tools)
- [x] **M2** download core (index, `.part` resume, progressive itag-18, retry/rotation)
- [x] **M3** `DownloadService` (dataSync FGS) + Details chip + Library section
- [x] **M4** local playback (offline-first, no resolver, no related fetch)
- [ ] **M5** audio-only (download progressive → extract AAC)
- [ ] **M6** storage cap, eviction, auto-delete
- [ ] **M7** signed release pipeline (signing done; GitHub Actions pending)

Verified on emulator: a real video downloaded (629,172 bytes + thumbnail) and played back with
airplane mode on — first video frame rendered, zero network resolves, no crashes.

Also verified on the **signed R8 release** (5.8 MB, versionCode 2 / 0.2.0): download and offline
playback both work, so shrinking and obfuscation have not broken InnerTube, serialization, or Media3.


---

## 1. Decisions

| Decision | Choice |
|---|---|
| Relationship to upstream | Fork, improved. Keep `upstream` remote and merge regularly. |
| First feature | Offline downloads |
| Distribution | Public GitHub Releases (signed APKs, adb sideload) |
| License | MIT — keep upstream copyright notice, add ours |
| Target | Wear OS 3+ (minSdk 30), round + square |

---

## 2. Fork setup (one-time)

Local clone already exists at `/home/sam/Projects/WearTubePlus` with `origin` = upstream.

1. Rename `origin` → `upstream`.
2. Create our repo (needs GitHub auth):
   ```bash
   gh repo fork channelramble/WearTube --clone=false   # optional, keeps fork linkage
   # or
   gh repo create <user>/WearTubePlus --public --source=. --remote=origin
   git remote rename origin upstream
   ```
3. Rebrand:
   - `applicationId` / `namespace`: `com.wateruse.weartube` → `com.hallide.weartubeplus` (done)
   - app label `WearTube` → `WearTubePlus`
   - icon `mipmap-nodpi/ic_launcher.png`
   - `rootProject.name` in `settings.gradle.kts`
   - README + LICENSE attribution
4. Remove author-specific artifacts:
   - hardcoded watch serial in `tools/wt.sh` (make it `adb devices`-driven, or env-only)
   - keystore password `"android"` → env vars / `local.properties`
   - debug intent hooks (`open_video`, `open_tab`, …) gated behind `BuildConfig.DEBUG`

---

## 3. Toolchain (blocking, currently missing)

Toolchain comes from **Android Studio** (installing now). Studio provides the JDK (JBR), SDK
manager, `adb`, and emulator. Paths to confirm after install: SDK root, `adb`, and the JBR.

- SDK packages: `platform-tools`, `platforms;android-37.1`, `build-tools`
- Gradle **wrapper** — the repo ships none. Generate it once Studio/Gradle is available, pinning
  the Gradle version required by AGP 9.3.1.
- Verify: `./gradlew :app:assembleDebug`
- Run target: Wear OS emulator (`system-images;android-35;wear;x86_64`) or a real watch over adb.

Note: if Studio is installed as a **Flatpak**, its SDK lives under the sandbox
(`~/.var/app/com.google.AndroidStudio/...`) and `adb` may need extra socket access. A native
package (`yay -S android-studio`) is simpler for command-line + watch-over-network work.

Deliverable: reproducible `assembleDebug` + a smoke-test checklist (sign-in, home, search,
playback, background audio).

---

## 4. Offline downloads

### 4.1 The key finding

`RangedDataSource` / `StreamUrls` documents this (measured on hardware):

- **Adaptive (video-only / audio-only) URLs are throttled**: ~0.5–2 MB per URL, then 403 forever.
  Surviving them requires re-issuing URLs from the player API every few seconds, hard-paced at
  4 s/rotation to avoid bot checks.
- **Progressive/muxed itag 18 (360p H.264+AAC) is NOT throttled**: measured 20.5 MB from a single
  URL, zero refusals.

⇒ **v1 downloads use the progressive muxed stream only.** One resolve, one sequential ranged
download, no rotation, no bot-check storm. This also gives audio and video in one file.

Audio-only downloads are still important on a watch (a 10-min 360p video is ~30–40 MB; its AAC
track is ~10 MB). We get them **without** touching throttled adaptive URLs: download the
progressive MP4, then remux the audio track locally with `MediaExtractor` + `MediaMuxer`, and
delete the video file. No network, no rotation, no transcoding.

### 4.2 Storage layout

```
filesDir/downloads/<videoId>/
    video.mp4      # progressive muxed, or deleted for audio-only
    audio.m4a      # audio-only extract
    thumb.jpg      # offline thumbnail
    meta.json      # title, channel, duration, quality, sizes, timestamps
filesDir/downloads.json   # index (atomic tmp+rename, same pattern as Store)
```

App-internal storage: no storage permission, survives normal use, removed on uninstall.
Expose usage in Settings and enforce a cap (default ~4 GB, configurable).

### 4.3 Data model

```kotlin
enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED }

data class Download(
    val video: Video,        // id, title, channel, thumb, duration, channelId
    val kind: Kind,          // VIDEO_360 or AUDIO
    val status: DownloadStatus,
    val bytesDone: Long,
    val bytesTotal: Long,    // from Content-Range when known
    val error: String?,
    val createdAt: Long,
)
```

`DownloadStore`: JSON index + `StateFlow<List<Download>>` for Compose, plus file helpers.

### 4.4 Download engine

`Downloader` singleton, **one active job** (watch battery/bandwidth):

1. `StreamResolver.resolve(videoId)` → pick muxed choice (itag 18).
2. Sequential bounded ranged GETs (`Range: bytes=X-(X+CHUNK-1)`, CHUNK ~256 KB–1 MB; open-ended
   ranges are refused by googlevideo).
3. Stream straight into `video.mp4.part`; persist `bytesDone` every few MB.
4. On 403/URL expiry: re-resolve once, continue from `bytesDone` (do **not** restart).
5. On completion: validate total size, rename to `video.mp4`, remux if audio-only, fetch thumb.
6. Retry with steep backoff (reuse the existing 250 ms → 4 s pattern).
7. Cancel/pause keeps `.part` for resume; app restart marks orphaned `DOWNLOADING` rows `PAUSED`.

No Media3 `DownloadManager`: the source is custom (`wt://` + rotation), so we own the byte loop.

### 4.5 Background execution

A watch user taps Download and lowers their wrist, so downloads must survive backgrounding.

- `DownloadService` foreground service, `foregroundServiceType="dataSync"`
  (+ `FOREGROUND_SERVICE_DATA_SYNC` permission on API 34+).
- Started from the foreground on user action (allowed under Android 14/15 rules).
- Low-importance notification with progress + cancel.
- Options: *Wi-Fi only* (default on), *only while charging* (default off).
- Do **not** use WorkManager for the transfer itself; it's a poor fit for a 40 MB adversarial
  HTTP loop. It can be added later for "retry queued downloads".

### 4.6 Playback integration

- `PlayerController.play(video)` checks `DownloadStore.localSource(videoId)` first.
- Local source:
  - muxed → `ProgressiveMediaSource(FileDataSource(video.mp4))`
  - audio-only → `ProgressiveMediaSource(FileDataSource(audio.m4a))`
- Offline-first rule: if a download exists, play it even when online (no network, no URL dance).
- When playing local, **skip** `StreamResolver`, `Repo.related`, and thumbnail URL fetches.
- Store `MediaMetadata` locally so title/channel/artwork work with no network.
- Quality menu shows the downloaded rendition as "Downloaded"; picking another quality while
  offline shows a clear message instead of a silent stall.
- Position saving/history/watch-later already local — unchanged.

### 4.7 UI touchpoints

| Screen | Change |
|---|---|
| `DetailsScreen` | Download chip: `Download` → `42% · Cancel` → `Downloaded ✓ · Delete` |
| `LibraryScreen` | New "Downloads" row + section: list, size each, total usage, play/delete |
| `PlayerScreen` | Small "Offline" badge when playing a local file |
| Settings | Wi-Fi only, auto-delete watched, storage cap, "clear downloads" |
| Player/download chip | Quality choice: *Video 360p* vs *Audio only* (with estimated size) |

### 4.8 Edge cases to handle explicitly

- Video has no muxed itag 18 (live, age-gated, some music) → fail with "Can't download this video",
  offer Audio only if an audio URL exists (via rotation path, v2).
- Long download crosses URL expiry (~6 h) → re-resolve, continue from offset.
- Rate-limit signature (fresh URL 403s) → pause with "YouTube is rate-limiting this network".
- Disk full / cap reached → pause, surface storage message, offer eviction of oldest.
- Deleting while playing → stop playback first, then delete.
- Interrupted process → `.part` resume, never a corrupt `DONE` row.
- Duplicate download tap → no-op / toggle cancel.
- Thumbnail fetch failure → placeholder, download still valid.

### 4.9 Explicitly out of scope for v1

- Adaptive 1080p/720p downloads (throttled URLs).
- Background sync of subscriptions for offline browsing.
- Downloads initiated from phone.
- DRM-protected content (the app already doesn't handle it).

---

## 5. Milestones

| # | Milestone | Deliverable |
|---|---|---|
| M0 | Toolchain + build | wrapper added, `assembleDebug` green, app runs on emulator/watch |
| M1 | Fork identity | package/app name/icon changed, upstream remote, licenses, CI skeleton |
| M2 | Download core | `Download`, `DownloadStore`, `Downloader`, resume/cancel, unit tests for range math |
| M3 | Background + UI | `DownloadService`, details chip, Library downloads section, progress states |
| M4 | Local playback | offline-first source selection, offline metadata/artwork, offline smoke test |
| M5 | Audio-only | download progressive → `MediaExtractor`/`MediaMuxer` extract → delete video |
| M6 | Storage + polish | cap, eviction, auto-delete, settings, error copy |
| M7 | Release | signed APK, GitHub Actions on tag, release notes, install docs |

Each milestone = one focused branch/PR against our own repo.

---

## 6. Testing

- **Unit** (pure JVM, no device): range/chunk arithmetic, resume offset, index serialization,
  eviction policy, source-selection rules.
- **Instrumented**: audio extraction, foreground service lifecycle, `.part` resume across process
  death.
- **Manual on watch/emulator**: airplane-mode playback, download → reboot → play, cancel mid-way,
  cap/eviction, 40 MB download on Wi-Fi vs Bluetooth.
- **Regression**: existing streaming path must keep working (no `StreamUrls` changes in v1).

CI: GitHub Actions
- PR: `./gradlew test assembleDebug`
- Tag `v*`: `assembleRelease` + upload APK artifact

---

## 7. Distribution

- Keystore generated once, stored **outside** the repo, injected via
  `WEARTUBE_KEYSTORE` / `WEARTUBE_STORE_PASSWORD` / `WEARTUBE_KEY_ALIAS` / `WEARTUBE_KEY_PASSWORD`.
- Never regenerate the key: it breaks update-over-install.
- Releases: `versionCode` bump, `versionName` semver, APK + SHA-256 checksum, install steps.
- Privacy note: no server, tokens stay on device. (Tokens are currently plain
  `SharedPreferences`; hardening is a follow-up.)
- Public-release risk to document honestly: unofficial YouTube clients violate YouTube's ToS, and
  the upstream TV OAuth client ID/secret is embedded in source. Personal-use builds are the
  low-risk path; public APKs carry takedown/abuse exposure. This is a known, accepted risk for
  this project — not something to paper over.

---

## 8. Release optimization backlog

Measured 2026-11-08: debug APK 68 MB; **signed** R8 release 5.8 MB (`classes.dex` 5.3 MB,
`resources.arsc` 0.5 MB). Size is not the problem — the debug build is.

Order of attack:

1. **[done] Ship/test R8 builds** — debug is unminified and logs heavily; not representative of
   watch performance. Signed release (v0.2.0) is verified on the emulator and ready to sideload.
2. **Storage I/O in `Store`** — every `history()` / `position()` call re-reads and re-parses the
   whole JSON file, and `savePosition()` rewrites `positions.json` every 5 s during playback.
   Move to an in-memory cache with periodic/on-stop flush.
3. **Home feed fan-out** — `Repo.homeFeed()` may fetch 20 channels × 5 videos (4 concurrent) plus
   related videos for 3 recents. Reduce the fan-out, cache the assembled feed, serve stale-while-
   revalidate.
4. **Download throughput** — progressive URLs are unthrottled, so raise `DownloadRange.CHUNK`
   from 512 KB to 1–2 MB (fewer requests), keep a single open output stream per response, and
   persist progress less often.
5. **Startup** — move `Downloads.init` / `Store` index parsing and Coil disk-cache setup off the
   main thread; add a baseline profile.
6. **UI** — request appropriately sized thumbnails, stable list keys everywhere, avoid
   `material-icons-extended` in debug builds, verify no per-frame allocations in `ScalingLazyColumn`.
7. **Battery** — Wi-Fi-only and charging-only download guards, prompt FGS shutdown, avoid position
   writes while backgrounded.
8. **Release hygiene** — gate debug intent hooks behind `BuildConfig.DEBUG`, strip `WTStream`/
   `WTDownload` verbose logs, versionCode scheme, signed GitHub Actions artifact.

## 9. Maintenance


- Weekly: `git fetch upstream && git log --oneline HEAD..upstream/main`; rebase/merge when the
  InnerTube/player-client layer changes (it is the part that breaks).
- Watch upstream issues for pinned-client failures; our downloads depend on itag 18 continuing to
  exist and stay unthrottled.
- Issue template that asks for watch model, app version, and logcat tag (`WTStream`).
