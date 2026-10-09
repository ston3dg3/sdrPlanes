# SdrPlanes

An Android app that receives ADS-B / Mode S transmissions from aircraft at 1090 MHz with a
USB RTL-SDR dongle and shows live traffic on a map. Everything runs on the phone in pure
Kotlin, including the USB driver and an offline aircraft database. No online
flight-tracking service is used.

## Hardware

| Item | Notes |
|---|---|
| Android phone/tablet | Android 11+ (API 30) with USB host (OTG) support |
| RTL-SDR dongle | RTL2832U (`0BDA:2838` / `0BDA:2832`) with an R820T/R820T2/R828D tuner (tested), RTL-SDR Blog V4/V4L, E4000 or FC0013. FC0012 and FC2580 sticks can't receive 1090 MHz well or at all |
| USB-OTG adapter | To connect the dongle to the phone |
| 1090 MHz antenna | The stock whip works. A tuned antenna outside or at a window works much better |
| Optional LNA/filter | Can be powered over the coax from the app (bias-tee) if the dongle supports it |

## How it works

```
RTL-SDR ──USB──▶ built-in driver (sdr/usb) ─┐
        └─▶ "RTL-SDR driver" app ─rtl_tcp─┤──▶ IQ 2.4 MS/s ─▶ ModeSDemodulator ─▶ ModeSDecoder
PC / Pi rtl_tcp server ──────────────────┘                                              │
                     aircraft.bin (offline DB) ──▶ AircraftTracker ◀────────────────────┘
                                                        │
                                  Map UI ◀─ ReceiverState (filters, theme, offline tiles)
```

- **`sdr/`**: three `SampleSource` options.
  - `UsbSampleSource` drives the dongle directly. `usb/` holds a Kotlin port of osmocom
    librtlsdr: the RTL2832U, and tuners R82xx (including RTL-SDR Blog V4/V4L input
    switching), E4000, FC0013, FC0012 and FC2580. Flaky tuner I2C writes are retried
    instead of ignored.
  - `RtlTcpSampleSource` speaks the rtl_tcp protocol.
  - `RtlSdrDriver` launches the third-party driver app.
- **`dsp/`**: Mode S demodulator for any sample rate. It integrates energy over exact
  half-bit windows, searches sub-sample phases and scores preambles against the noise
  floor, then tries the best alignments until one passes the CRC.
- **`decode/`**:
  - CRC-24 with 1- and 2-bit repair (only for aircraft already known).
  - DF0/4/5/11/16/17/18/20/21.
  - ADS-B identification and category, airborne and surface position (CPR), velocity,
    emergency and TCAS RA broadcast (TC 28), target state (TC 29 v1/v2) and operational
    status (TC 31).
  - Comm-B BDS 1,0, 1,7, 2,0, 3,0, 4,0, 4,4, 4,5, 5,0 and 6,0, inferred from content with
    the status-bit and range checks of pyModeS.
- **`db/`**: `AircraftDb` memory-maps the bundled database (about 570k aircraft:
  registration, ICAO type, model, year, military flag) and the ICAO address blocks
  (country). The data comes from Mictronics via
  [wiedehopf/tar1090-db](https://github.com/wiedehopf/tar1090-db). Rebuild it with
  `python tools/build_aircraft_db.py`.
- **`model/`**: merges messages into per-aircraft state.
  - It keeps trails, the on-ground flag, autopilot targets, air data, weather, TCAS RA and
    emergencies.
  - It uses BDS 1,7 capabilities and ADS-B speed/track to reject inconsistent Comm-B
    replies.
  - `AircraftFilter` searches, filters and sorts for both the list and the map.
- **`service/`**: a foreground service with a wake lock runs the pipeline, so reception
  continues with the screen off. It reconnects with backoff (1–15 s) and records raw IQ on
  request.
- **`ui/`**:
  - Jetpack Compose and a MapLibre map, with OpenFreeMap dark/positron styles recoloured
    from `colour_palette.md`.
  - Dark, light and system themes.
  - A contacts list with filters, an aircraft card and settings.
  - Offline map download.

## Running

**Build:** open the project in Android Studio and run `app`, or use the command line:

```sh
./gradlew :app:installDebug
```

**On a phone (default, built-in USB driver):**
1. Plug in the dongle and allow SdrPlanes to access it. Tick "always open" to skip the
   prompt next time.
2. Grant location and notification permissions, then press ▶.

**Other sources (Settings → Signal source):**
- **RTL-SDR driver app:** uses the [RTL-SDR driver](https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro)
  app (`marto.rtl_tcp_andro`).
- **Network:** run `rtl_tcp -a 0.0.0.0 -p 1234` on a PC or Pi with the dongle, then enter
  its host. For the emulator, run `adb reverse tcp:1234 tcp:1234` and use `127.0.0.1`.

**Settings:**

| Setting | Options |
|---|---|
| Gain | Fixed or tuner AGC; default 49.6 dB |
| Frequency correction | ppm |
| Sample rate | 2.4 MS/s (default, best decoding) or 2.0 MS/s (less CPU) |
| Bias-tee | Powers a bias-tee LNA/filter (USB and rtl_tcp sources) |
| Appearance | System, Dark or Light |
| Offline map | Downloads 300 km around you, about 110 MB (use Wi-Fi) |
| Recording | Saves 30 s of raw IQ to Download/SdrPlanes, then offers Share |

**Contacts list:** search by callsign, registration, type, ICAO or country; hide ground
traffic; show only aircraft with a position, or only military; set an altitude range; and
cycle the sort order. The filter also applies to the map, and the HUD shows "shown/total".

## Tests

```sh
./gradlew :app:testDebugUnitTest          # decoder vectors, Comm-B, demodulator, tracker, filter, DB
./gradlew :app:connectedDebugAndroidTest  # UI smoke test, pipeline + reconnect via fake rtl_tcp
RTL_TCP_HOST=127.0.0.1 ./gradlew :app:testDebugUnitTest --tests '*RtlTcpLiveTest*'     # live rtl_tcp
IQ_FILES=a.bin IQ_RATE=2400000 ./gradlew :app:testDebugUnitTest --tests '*IqFileBenchmarkTest*'
TUNE=1 ./gradlew :app:testDebugUnitTest --tests '*SensitivityTest*'                    # threshold sweep
```

- `IqFileBenchmarkTest` decodes recorded IQ files, including the app's own recordings.
- `connectedDebugAndroidTest` uninstalls the app afterwards. On devices with a work
  profile or Secure Folder, run the test APK with `am instrument --user 0` instead.

`SensitivityTest` simulates random-phase frames with noise. On that model, the current
decoder recovers these shares of frames:

| SNR | 2.4 MS/s + 2-bit repair | Previous demodulator (2 MS/s + 1-bit repair) |
|---|---|---|
| 12 dB | 91% | 41% |
| 15 dB | 100% | 60% |

## Dependencies

- **Build:** AGP 9.4, Kotlin 2.2, compileSdk/targetSdk 37, minSdk 30, Java 11 (the JDK bundled
  with Android Studio works). Python 3 is only needed to rebuild the aircraft database.
- **Libraries:** Jetpack Compose (BOM 2026.02), Material 3, AndroidX Lifecycle, MapLibre
  Android 13.6.
- **Runtime:** internet access for map tiles from `tiles.openfreemap.org`, unless the area is
  downloaded. The driver app or an rtl_tcp server is only needed for those source modes.

## License

SdrPlanes is free software under the **GNU GPL v3.0 or later** (see [LICENSE](LICENSE)).

- **Code:** the USB driver is ported from osmocom librtlsdr (GPL-2.0-or-later). The Comm-B
  validation rules follow pyModeS (GPL-3.0).
- **Aircraft data:** from the Mictronics aircraft database via wiedehopf/tar1090-db. No
  licence is stated upstream; it is credited in the app's settings.
- **Map:** © OpenFreeMap, OpenMapTiles, OpenStreetMap contributors.
- **Fonts:** Orbitron and Share Tech Mono (SIL OFL, licences in `app/src/main/assets/licenses/`).

## Current limitations

- **Untested tuners:** only the R820T is tested on hardware. E4000, FC0013, Blog V4/V4L and
  the bias-tee are ports without a test device. FC0012 and FC2580 are detected, but the
  FC0012 can't tune 1090 MHz, and the FC2580 only reaches it through its L-band path.
- **Signal level:** a stock antenna indoors gives weak signals. Range is mostly limited by
  the antenna.
- **2.0 MS/s:** pulses can straddle samples, so this rate decodes clearly worse than 2.4 MS/s.
- **Comm-B:** registers are inferred, not transmitted. An ambiguous reply is dropped unless
  ADS-B data or the BDS 1,7 capability report settles it.
- **Surface positions:** need the receiver location, or an earlier fix, within about 80 km.
- **Driver-app mode:** the driver exits when the connection drops, so that mode can't
  reconnect automatically.
- **Aircraft database:** a snapshot from build time; there is no in-app update yet.
- **Offline map:** one area of 300 km at zoom ≤ 10. Closer zoom levels need a connection.
- **Data export:** no SBS/Beast output or log yet (see TODO.md).
- **Windows rtl_tcp:** it can hang when a client disconnects abruptly. Restart it.
