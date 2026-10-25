# DiPlay · Lynk & Co CS11 (GKUI / Android 4.4.3) fork

**CarPlay receiver for one specific head unit: the Lynk & Co CS11** (`model=CS11`, ECARX/GKUI
platform, **Android 4.4.3 / API 19 / Dalvik**, Freescale **i.MX6Q**, 1280×720 logical on a
1920×1080 panel). Wired **and** wireless CarPlay, plus navigation/media cards on the instrument
cluster through the vendor DIM interface.

This is a long-lived fork of [DiPlay](https://github.com/shihabal3amri/DiPlay) (GPL-3.0). Upstream
targets modern BYD head units (Android 9+); **this fork removed the BYD/DiLink adaptation** and only
claims support for the CS11. See [Scope](#scope) before installing it anywhere else.

> Not an Apple-certified product. CarPlay and its icon belong to Apple Inc. No Apple, Lynk & Co,
> Geely or ECARX affiliation or endorsement is implied.

- Package: `com.shihab.diplay` (`com.shihab.diplay.hudtest` for the debug variant)
- Version: `0.2.21-cs11.1` (`-hud-test`), versionCode 40
- Engines: `minSdk 19`, `targetSdk 19`, `armeabi-v7a` only, core library desugaring on, multidex

## Status (2026-10-12)

| Area | State |
|---|---|
| Wireless CarPlay (car's own hotspot + Bluetooth iAP2 hand-off) | **Working** on the CS11 |
| Wired CarPlay (USB + userspace NCM bridge) | **Working**, first on-screen 2026-10-10 |
| Cluster navigation card (arrow / street / distance / ETA) | **Working**, verified in the car |
| Cluster media card (title / artist / album / progress) | **Working**, verified in the car |
| Cluster album artwork | **Not solved** — the vendor reader never draws our URI (HTTP tier added, verdict experiment pending) |
| Touch latency / video smoothness | Tuned: receive window, aging threshold, allocation cuts, `TCP_NODELAY`, thread priority |
| API-19 hardening | Compat sweep + source-scanning guard tests |
| Wired/wireless stability gaps | Known, not fixed yet (first-frame watchdog, USB detach broadcast, visible failures, mode convergence) |
| Unit tests | `:shared` **1072 / 0 failures**, `:common` **395 / 0**, nothing skipped |

Detailed engineering notes (measurements, rejection lists, car-session evidence) are kept **outside**
this repository by project convention — this README is the code-level entry point only.

## Build

Requirements: **JDK 21**, the Android SDK/NDK as configured by AGP 9.3.0, and **offline** Gradle
(the wrapper points at a mirror; `--offline` is expected).

```powershell
cd CarPlay-CS11
.\gradlew.bat :mobile:assembleStandaloneDebug --console=plain --offline
# -> mobile\build\outputs\apk\debug\mobile-debug.apk  (~10.89 MB)
```

- The **accessory identity is committed** (`mobile/src/main/auth-assets/offline-mfi/`, see
  [Authentication](#authentication)), so a clone builds and connects without any extra input.
  `DIPLAY_AUTH_ASSETS_DIR` still works and **replaces** that identity for a build.
- ⚠️ Assemble with `:mobile:clean` when the APK is unexpectedly large: an incremental packaging step
  left ~5 MB of dead space in a 15.87 MB APK once; a clean build is ~10.89 MB.
- The repository also contains `:vpndialogs-stub`, the small stub module the VPN dialog dependency
  needs; it is part of the build graph, not an optional extra.

## Test

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest --continue --offline
# expected: :shared 940 / 0 failures, :common 590 / 0 failures, 0 skipped
```

The **failure set must stay empty**; new tests may increase the counts. Never run two Gradle builds
at once (they contend on the same build outputs).

## Scope

- The **only** supported device is the CS11 described above. Everything here was tuned against its
  Dalvik runtime, its vendor codecs and its kernel — behaviour elsewhere is not claimed. The APK
  targets API 19 and cannot be installed on Android 14+ without
  `adb install --bypass-low-target-sdk-block`; the tablet bench is no longer used.
- **Wired** CarPlay uses the userspace NCM bridge (raw USB + lwIP) and needs no root. The kernel
  `cdc_ncm` path and its root fire were removed (docs/_digest/63).
- The app does not modify `/system`, does not install patched third-party APKs, and does not require
  root for the wireless path.

## Authentication

`DiPlayBootstrap` copies `assets/offline-mfi/{identity.pk8,certificate.p7b}` into the app's private
directory on first start, and `LocalMfiAuthenticationClient` uses them to complete accessory
authentication. The pair committed here is the **same identity upstream DiPlay already ships inside
its public release APK** (`DiPlay-0.2.12.apk`, byte-identical), so the repository does not widen its
exposure — but note that it is an Apple-issued accessory identity whose **private key anyone can
extract**, and that publishing it may conflict with MFi terms and can lead to revocation.

For anything beyond personal use, **replace it**: drop your own pair into
`mobile/src/main/auth-assets/offline-mfi/` or point `DIPLAY_AUTH_ASSETS_DIR` at a directory with
`offline-mfi/{identity.pk8,certificate.p7b}`. The build fails if the active identity is missing, and
any *other* `.pk8/.p7b/.key/.pem/.p12/.pfx/.jks/.keystore` in the assets fails the build as well.

## Layout

```
shared/   protocol + core: AirPlay (RTSP, H.264, AAC/LPCM, HID, NTP, crypto), iAP2, USB/NCM,
          media pipeline, network/hotspot, cluster bridge, API-19 compat layer
common/   Android host: activities, settings UI, persistence, notifications, permissions,
          corner-mask overlay, artwork provider
mobile/   the packaged application + the committed accessory identity
docs/     upstream documentation kept as-is (build, install, compatibility, release notes, licenses)
site/ asset/  upstream leftovers, unused on the CS11
```

## Documentation

- [`docs/BUILD.md`](docs/BUILD.md) — upstream guide plus a "this fork" note at the top with the
  commands that actually apply here; [`docs/INSTALL.md`](docs/INSTALL.md) and
  [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) describe the BYD target, not this fork.
- [`docs/UPSTREAM-README.md`](docs/UPSTREAM-README.md) — upstream README as published.
- [`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md), `docs/licenses/` — credits and
  licenses.

## Source and credits

GPL-3.0 ([LICENSE](LICENSE)). Based on [DiPlay](https://github.com/shihabal3amri/DiPlay) by
shihabal3amri, itself based on [xcertplay](https://github.com/shilapi/xcertplay); the home/settings
UI and website adapt [DiAuto](https://github.com/shihabal3amri/DiAuto) (AGPL-3.0, licence text in
`docs/licenses/`). The bundled Opus codec (`shared/src/main/jniLibs/armeabi-v7a/libdiplay_opus.so`)
is Opus/libopus (BSD, see `docs/Opus-LICENSE.txt`).

Ideas and mechanisms were also compared against the open-source **Geely H52 / Android 4.3** port
[`xikai6282/DiPlay-Geely-Android43`](https://github.com/xikai6282/DiPlay-Geely-Android43) (same
ECARX/GKUI generation): USB short-write resumption with one shared deadline, the persistent USBMUX
read request, the video-thread priority value and the iAP2 `0x5703` BSSID field follow what that
project reported. No code was copied; each change was reimplemented here with its own tests.

Preserve the upstream and DiAuto notices when redistributing modifications.
