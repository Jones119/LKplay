# Compatibility

This is the DiPlay **CS11 fork**: one head unit (Lynk & Co CS11, Android 4.4.3 / API 19 / Dalvik) and
`targetSdk = 19`. It is not an Apple-certified CarPlay accessory.

| Area | Scope |
| --- | --- |
| Head unit | CS11 only: Android 4.4.3 / API 19 / Dalvik / 32-bit armv7 |
| Phone | Standard, non-jailbroken iPhone with CarPlay enabled; device/iOS compatibility varies |
| Wired | userspace NCM bridge (raw USB + lwIP); the kernel `cdc_ncm` netdev was removed |
| Wireless | the car's own hotspot (manual) is the supported backend; Wi-Fi Direct and "existing Wi-Fi" are not available on API 19 |
| Video | default H.264 / 30 fps |
| Build | `minSdk 19`, `targetSdk 19`, `armeabi-v7a` only, core library desugaring on, multidex |

## Not supported

- Any Android version newer than 4.4 is not a target. An APK with `targetSdk 19` cannot be installed
  on Android 14+ without `adb install --bypass-low-target-sdk-block`, and the API 21+ code paths were
  removed rather than kept as dormant branches.
- Other head units and other firmwares are not covered; the BYD/DiLink adaptation is gone.

## Known limitations

- USB requires a data port and correct host/device-role behaviour.
- The car's always-on voice assistant holds the microphone; the microphone path walks the source
  ladder and reports when every source is blocked.
- Calls, Siri, background reconnection, long journeys and future iOS releases need broader testing.
- Reports record requested frequencies, station association state and fallback failures. Wi-Fi
  credentials and protocol payloads are excluded. A successful hotspot is not itself a successful
  CarPlay session.
