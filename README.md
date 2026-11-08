# WearTubePlus

A standalone YouTube client for Wear OS, with **offline downloads**.

Forked from [`channelramble/WearTube`](https://github.com/channelramble/WearTube) (MIT). Runs
entirely on the watch — no phone app, no companion service, no bridge.

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/Hallide420/WearTubePlus?include_prereleases)](https://github.com/Hallide420/WearTubePlus/releases)

## Download

Signed APKs are published on the [Releases page](https://github.com/Hallide420/WearTubePlus/releases).
Requires **Wear OS 3+** (API 30).

Wear OS has no sideload UI, so install over adb:

```bash
# Enable Settings → Developer options → Wireless debugging on the watch,
# then read the IP:port from that screen. The port changes when the watch sleeps.
adb connect <watch-ip>:<port>
adb install WearTubePlus-0.2.2.apk
```

The app installs as `com.hallide.weartubeplus`, so it can coexist with upstream WearTube.

## Offline downloads

- **Save from the watch** — open a video, tap **Download** on its details page.
- **Survives your wrist going down** — transfers run in a `dataSync` foreground service and
  continue while the screen is off.
- **Resumable** — a partial download picks up where it stopped, including after a reboot.
- **Offline-first playback** — a completed download plays straight from local storage: no stream
  resolution, no URL rotation, no network at all.
- **Manage in Library → Downloads** — progress, cancel, retry, delete one or delete all.
- **Battery-friendly** — optionally pause downloads on mobile data or unless the watch is charging
  (**Library → Settings**).

Current limits, by design for the first release:

- Downloads are **360p progressive video only**. That is the one stream YouTube serves without a
  per-URL byte budget, so it is the only one that can be fetched reliably in one pass.
- **Audio-only downloads** are not implemented yet; audio-only *streaming* still works.
- There is no storage cap or automatic eviction yet.

## Features

Inherited from WearTube and kept working:

- **Your real feed** — sign in with a TV-style device code (type a short code on your phone; the
  watch never sees your password) and Home shows your actual recommendations and subscriptions.
  Signed out, it builds a feed from channels you follow locally.
- **Search** with the watch keyboard or voice, plus suggestions
- **Channels** — browse uploads, subscribe/unsubscribe (syncs to your account)
- **Playlists** — your account's playlists, plus Play All queueing
- **Player** — quality picker (144p–480p), speed control, ±10s, tap-to-seek, rotating bezel for
  volume, audio-only mode
- **Background audio** — keeps playing when you leave the app, with a chip on the watch face and
  full system media controls
- **Mini player** — persistent play/pause pill on every screen
- **Comments**, autoplay-next, Watch Later, history and resume positions

Added by this fork:

- **Offline downloads** and local playback
- Separate identity and preferences, so it does not collide with upstream
- Portable developer tooling (no hardcoded device serials or machine paths)
- Signed R8 release builds

## Building

```bash
export JAVA_HOME=/path/to/jdk17+
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # minified, resource-shrunk APK
```

Requires a JDK and Android SDK platform 37 / build tools 36. Release builds are signed from
`WEARTUBE_KEYSTORE`, `WEARTUBE_STORE_PASSWORD`, `WEARTUBE_KEY_ALIAS` and `WEARTUBE_KEY_PASSWORD`.
Keystores are never committed — back yours up, because losing it means future releases cannot be
installed as updates.

To build and install on a connected watch in one step:

```bash
tools/install-watch.sh                # debug
tools/install-watch.sh --release      # signed release
```

## How it works

WearTubePlus talks to YouTube's InnerTube endpoints directly and plays streams with
Media3/ExoPlayer. Two details do most of the heavy lifting:

- **Pinned player client.** Current client versions return SABR responses with no stream URLs at
  all. The app pins known-good versions and falls back across several clients.
- **Per-URL byte budgets.** A `googlevideo` URL serves roughly 0.5–2 MB in total and then refuses
  everything, regardless of how small each range is. The app tracks a budget per URL, prefetches a
  replacement before it runs out, and swaps transparently underneath ExoPlayer, so a refusal never
  reaches the decoder.

Downloads sidestep all of that by using the progressive stream, which has no such budget, and
writing it to a `.part` file the app owns.

## Roadmap

See [`PLAN.md`](PLAN.md). Next up: audio-only downloads, storage limits and eviction, a release
workflow on tags, and the performance work in §8.

## Notes

This is an unofficial client and is not affiliated with, endorsed by, or supported by YouTube or
Google. It is provided for personal use. Signing in uses a public TV OAuth client that ships with
the source; use of the app is subject to YouTube's terms of service.

## License

MIT — see [`LICENSE`](LICENSE).

Based on [WearTube](https://github.com/channelramble/WearTube) by
[channelramble](https://github.com/channelramble/WearTube). The original copyright notice is
retained.
