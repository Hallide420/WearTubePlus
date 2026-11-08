# WearTubePlus

A standalone YouTube client for Wear OS, forked from
[`channelramble/WearTube`](https://github.com/channelramble/WearTube) (MIT).
Runs entirely on the watch — no phone app, no companion, no bridge.

**Status: pre-release.** The upstream feature set works; offline downloads are in development.

## What this fork changes

- New identity: `com.hallide.weartubeplus`, separate app install alongside upstream
- Offline downloads (in progress — see [`PLAN.md`](PLAN.md))
- Portable developer tooling (no hardcoded device serial or machine paths)
- Signed public APK releases

## Features (inherited from WearTube)

- **Your real feed** — sign in with a TV-style device code (you type a short code on your phone;
  the watch never sees your password) and Home shows your actual YouTube recommendations and
  subscriptions. Signed out, it builds a feed from channels you follow locally.
- **Search** with the watch keyboard or voice, plus suggestions
- **Channels** — browse uploads, subscribe/unsubscribe (syncs to your account)
- **Playlists** — your account's playlists, plus Play All queueing
- **Player** — quality picker (144p–480p), speed control, ±10s, tap-to-seek, rotating bezel for
  volume, audio-only mode
- **Background audio** — keeps playing when you leave the app, with a chip on the watch face and
  full system media controls
- **Mini player** — persistent play/pause pill on every screen
- **Comments**, autoplay-next, Watch Later, history and resume positions

## Install

Wear OS doesn't have a sideload UI, so install over adb:

```
adb connect <watch-ip>:<port>
adb install WearTubePlus.apk
```

Or build and install in one step with `tools/install-watch.sh <watch-ip>:<port>`.

For the optimized build, use `tools/install-watch.sh --release [<watch-ip>:<port>]`. It sources the
signing credentials from `$WTP_SIGNING_ENV` (default `~/.config/wtp/signing.env`). The release APK
is signed with a different key than the debug one, so the debug install must be uninstalled first
(`adb uninstall com.hallide.weartubeplus`); that also deletes settings and downloads.


Enable **Settings → Developer options → Wireless debugging** on the watch first and read the
IP/port from that screen. The port changes whenever the watch sleeps.

## Building

```
./gradlew assembleRelease
```

Requires a JDK and the Android command-line tools with `platforms;android-37.1`. Release builds are
signed from `WEARTUBE_KEYSTORE` / `WEARTUBE_STORE_PASSWORD` / `WEARTUBE_KEY_ALIAS` /
`WEARTUBE_KEY_PASSWORD` (see `~/.config/wtp/signing.env`). Keystores are never committed — back
yours up if you intend to publish updates.

## How it works

WearTubePlus talks to YouTube's InnerTube endpoints directly and plays streams with
Media3/ExoPlayer. Two details do most of the heavy lifting:

- **Pinned player client.** Current client versions return SABR responses with no stream URLs at
  all. The app pins known-good versions and falls back across several clients.
- **Per-URL byte budgets.** A googlevideo URL serves roughly 0.5–2MB in total and then refuses
  everything, regardless of how small each range is. The app tracks a budget per URL, prefetches a
  replacement before it runs out, and swaps transparently underneath ExoPlayer, so a refusal never
  reaches the decoder.

## Notes

This is an unofficial client and is not affiliated with, endorsed by, or supported by YouTube or
Google. It is provided for personal use.

## License and attribution

MIT — see [`LICENSE`](LICENSE). Based on WearTube by
[channelramble](https://github.com/channelramble/WearTube); the original copyright notice is
retained.
