# SdrPlanes

An Android app that receives ADS-B / Mode S transmissions from aircraft at 1090 MHz with a
USB RTL-SDR dongle and shows live traffic on a map. Decoding runs on the phone in pure
Kotlin. No online flight-tracking service is used.

## Hardware

| Item | Notes |
|---|---|
| Android phone/tablet | Android 11+ (API 30) with USB host (OTG) support |
| RTL-SDR dongle | RTL2832U-based (`0BDA:2838` / `0BDA:2832`), tested with an R820T tuner |
| USB-OTG adapter | To connect the dongle to the phone |
| 1090 MHz antenna | The stock whip works; a tuned antenna placed outside or at a window works much better |

## How it works

```
RTL-SDR ──USB──▶ "RTL-SDR driver" app ──rtl_tcp (127.0.0.1:1234)──▶ SdrPlanes
                                                                        │
  IQ samples (2 MS/s) ─▶ ModeSDemodulator ─▶ ModeSDecoder ─▶ AircraftTracker ─▶ Map UI
```

- **`sdr/`**: `SampleSource` abstraction. `RtlTcpSampleSource` speaks the rtl_tcp protocol.
  `RtlSdrDriver` launches the [RTL-SDR driver](https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro)
  app through an `iqsrc://` intent.
- **`dsp/`**: Mode S preamble detection and PPM bit slicing on the IQ magnitude.
- **`decode/`**: CRC-24 (with single-bit error correction), DF0/4/5/11/16/17/18/20/21, ADS-B
  identification, airborne position (CPR global and local decoding) and velocity.
- **`model/`**: merges messages into per-aircraft state. Aircraft expire after 60 s, and
  positions more than 500 km from the receiver are rejected.
- **`service/`**: a foreground service runs the pipeline, so reception continues with the
  screen off. Results are shared with the UI through `ReceiverState` (StateFlows).
- **`ui/`**: Jetpack Compose and a MapLibre map (OpenFreeMap dark style, recoloured with the
  retro palette in `colour_palette.md`), plus a contacts list, aircraft details and settings.

## Running

**Build:** open the project in Android Studio and run `app`, or use the command line:

```sh
./gradlew :app:installDebug
```

**On a phone (normal use):**
1. Install the **RTL-SDR driver** app (`marto.rtl_tcp_andro`) from the Play Store.
2. Plug in the dongle. Android offers to open SdrPlanes. Grant USB, location and
   notification permissions.
3. Press start. SdrPlanes launches the driver, which opens rtl_tcp on localhost.

**Network mode (emulator or remote dongle):** run `rtl_tcp -a 0.0.0.0 -p 1234` on a PC with
the dongle attached. In the app settings, switch to *Network* and enter the host. For the
emulator, run `adb reverse tcp:1234 tcp:1234` and use `127.0.0.1` as the host.

Settings: gain (fixed value or tuner AGC; default 49.6 dB), frequency correction (ppm), and
host/port.

## Tests

```sh
./gradlew :app:testDebugUnitTest              # demodulator, decoder, tracker
./gradlew :app:connectedDebugAndroidTest      # UI smoke test + pipeline via fake rtl_tcp server
RTL_TCP_HOST=127.0.0.1 ./gradlew :app:testDebugUnitTest --tests '*RtlTcpLiveTest*'   # live dongle
```

## Dependencies

- **Build:** AGP 9.4, Kotlin 2.2, compileSdk/targetSdk 37, minSdk 30, Java 11 (the JDK bundled
  with Android Studio works)
- **Libraries:** Jetpack Compose (BOM 2026.02), Material 3, AndroidX Lifecycle, MapLibre
  Android 13.6
- **Runtime:**
  - the external RTL-SDR driver app, or any rtl_tcp server
  - internet access for map tiles from `tiles.openfreemap.org`
- **Fonts:** Orbitron and Share Tech Mono (SIL OFL, licences in `app/src/main/assets/licenses/`)

## Current limitations

- There is no built-in USB driver. A local dongle needs the third-party RTL-SDR driver app;
  a native librtlsdr backend is planned behind `SampleSource`.
- The sample rate is fixed at 2 MS/s with a simple demodulator. Weak or overlapping
  signals are missed more often than with dump1090/readsb.
- Only airborne positions are decoded. Surface positions, operational status and other
  type codes are counted but ignored.
- Error correction covers single-bit errors on 112-bit frames only.
- The map needs a network connection. There are no offline tiles.
- Aircraft have no history, trails, recording or export, and there is no aircraft database
  for registration or type.
- The UI uses a fixed dark theme.
- On Windows, `rtl_tcp` can hang when a client disconnects abruptly. Restart it.
