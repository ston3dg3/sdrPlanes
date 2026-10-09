# TODO

## Reception
- [x] Native USB backend: pure-Kotlin RTL2832U + R820T/R828D driver (`sdr/usb/`), no driver app needed
- [x] Improve demodulator sensitivity: 2.4 MS/s option, preamble scoring against the noise floor, sub-sample phase search
- [x] Two-bit error correction for DF17/18
- [x] Bias-tee toggle for powered LNAs and filters
- [x] Auto-reconnect when the rtl_tcp connection or USB stream drops
- [x] Other tuners in the USB driver: E4000, FC0013, FC0012 and FC2580 ported; RTL-SDR Blog V4 / V4 Lite input switching
- [x] Optional IQ recording from the app (Settings → Recording, saved to Download/SdrPlanes)
- [ ] Hardware test of the bias-tee and of the E4000 / FC00xx / FC2580 / Blog V4 paths (needs those dongles)

## Decoding
- [x] Surface position (TC 5–8)
- [x] Target state & status (TC 29, versions 1 and 2), operational status / ADS-B version (TC 31), emitter category
- [x] Emergency status and TCAS RA broadcast (TC 28)
- [x] Comm-B (DF20/21) BDS 1,0 / 1,7 / 2,0 / 3,0 / 4,0 / 4,4 / 4,5 / 5,0 / 6,0 with inference and cross-checks

## Map & UI
- [x] Offline map: download 300 km around the receiver (dark + light style, zoom ≤ 10)
- [x] Track trails (position history per aircraft)
- [x] Aircraft database lookup (registration, type, model, country, military) – bundled, offline
- [x] Filters and sorting (search, altitude range, ground, position, military; list and map)
- [x] Light theme / follow system theme
- [x] Show surface traffic distinctly (small muted icons under airborne traffic)
- [ ] In-app update of the aircraft database (currently refreshed by rebuilding with `tools/build_aircraft_db.py`)

## Data
- [ ] Recording and export (SBS/BaseStation, Beast or JSON)
- [ ] Optional output to a network feed or local server (e.g. port 30003)
- [ ] Range and statistics view (max range, message rate history)

## Tooling
- [ ] CI build and unit tests (GitHub Actions)
- [ ] Release signing and versioning
- [ ] Release build performance check (DSP CPU measured only on debug builds so far)
