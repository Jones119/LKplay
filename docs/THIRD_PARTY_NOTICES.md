# Credits and license notices

## Receiver

DiPlay is a modified version of [xcertplay by shilapi](https://github.com/shilapi/xcertplay). The upstream receiver is licensed under GNU GPL version 3; the full text is in `LICENSE` and the original README is retained in `docs/UPSTREAM-README.md`.

Upstream credits [LIVI](https://github.com/f-io/LIVI) and [Showcase](https://github.com/amineross/showcase) for protocol research. Existing source comments and attribution are preserved.

## Home and settings UI

`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` adapts the palette, visual arrangement and interface copy of the [DiAuto project](https://github.com/shihabal3amri/DiAuto). DiAuto's source is licensed under AGPL version 3. The UI file is marked AGPL-3.0-only; its license text is included in `docs/licenses/DiAuto-AGPL-3.0.txt`.

## CarPlay icon

The unmodified icon was obtained from Apple's developer site at:

https://developer.apple.com/assets/elements/icons/carplay/carplay-96x96_2x.png

CarPlay and the CarPlay icon are Apple Inc. marks/assets. This asset is not covered by the project's open-source code license. Its use here does not imply Apple approval or certification.

## Runtime dependencies

- AndroidX and Jetpack Compose — Android Open Source Project; Apache License 2.0.
- Kotlin standard library — JetBrains; Apache License 2.0.
- Bouncy Castle 1.79 — The Legion of the Bouncy Castle Inc.; Bouncy Castle license (MIT-style).
- JmDNS 3.6.3 — JmDNS contributors; Apache License 2.0.
- SLF4J — QOS.ch; MIT license.

Gradle dependency declarations and version catalog accompany the source. License files available in the resolved artifacts are included under `docs/licenses/dependencies/`.

## Experimental authentication data

The public preview APK includes an accessory certificate/key pair recovered from public Carlinkit C2Air Allwinner V821 firmware during the owner's local investigation. These data are not newly generated Apple-issued credentials for DiPlay and are not relicensed as project source code. They are bundled in the preview APK to reproduce the offline experiment; continued acceptance and suitability for general distribution are unresolved. The source archive does not contain the private key, and the separate Android APK-signing key is never distributed.

## Download website

The static site layout, CSS and generator adapt DiAuto (AGPL-3.0). The AGPL license text is included with the source.

## Opus (CS11 build)

`shared/src/main/jniLibs/armeabi-v7a/libdiplay_opus.so` bundles libopus 1.x together with a thin JNI
binding for `com.shilapi.xcertplay.media.LegacyNativeOpus`. It is required because this head unit ships
no Opus codec while wireless CarPlay encodes the microphone uplink and the call/Siri downlink in Opus.
Opus is BSD-3-Clause (Copyright 2001-2023 Xiph.Org, Skype Limited, Octasic, Jean-Marc Valin,
Timothy B. Terriberry, CSIRO, Gregory Maxwell, Mark Borgerding, Erik de Castro Lopo, Mozilla, Amazon);
the license text is in `docs/Opus-LICENSE.txt`. The binary was taken from the owner's analysis copy of
EasyPlay (a DiPlay fork for this same head unit that shares this package name), not from a redistributed
store build.

## Historic: HUD maneuver icons (removed in the CS11 build)

The maneuver PNGs previously shipped under `shared/src/main/assets/byd-hud-icons`, imported from BYDMate
(Required Notice: Copyright AndyShaman, https://github.com/AndyShaman/BYDMate, PolyForm Noncommercial 1.0.0),
were removed in the CS11 build together with the rest of the vendor-specific HUD/cluster adaptation: the
target head unit exposes no such display, so nothing referenced them. This notice is kept for provenance.

## CarPlay car icon and label (CS11 build)

The image shipped as `common/src/main/res/raw/ic_car_home.png` is the icon iOS renders next to the
"back to the car" entry in CarPlay. It carries the Lynk & Co mark (the LYNK&CO wordmark with 领克),
cropped from an image supplied by the vehicle owner, and is used only to identify this app on the
owner's own head unit; "Lynk & Co" and the Lynk & Co logo are trademarks of their respective owner and
this project claims no affiliation. The label iOS shows together with the icon (`oemIconLabel`, stored
as `oem_label`) defaults to 领克 and can be changed in the app's settings. The image it replaced (the
BYD wordmark) was removed with the rest of the vendor-specific adaptation.


