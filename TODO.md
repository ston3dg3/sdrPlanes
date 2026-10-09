# TODO

## Reception
- [ ] Native USB backend (librtlsdr via JNI or a pure-Kotlin RTL2832U driver) as a `SampleSource`, removing the need for the RTL-SDR driver app
- [ ] Improve demodulator sensitivity: 2.4 MS/s option, better preamble scoring, phase correction (dump1090/readsb style)
- [ ] Two-bit error correction for DF17/18
- [ ] Bias-tee toggle for powered LNAs and filters
- [ ] Auto-reconnect when the rtl_tcp connection drops

## Decoding
- [ ] Surface position (TC 5–8)
- [ ] Operational status / aircraft category display (TC 29, 31)
- [ ] Comm-B (DF20/21) BDS registers: selected altitude, Mach, IAS

## Map & UI
- [ ] Offline map tiles / cached region
- [ ] Track trails (position history per aircraft)
- [ ] Aircraft database lookup (registration, type, operator) by ICAO address
- [ ] Filters and sorting (altitude, distance, callsign)
- [ ] Light theme / follow system theme

## Data
- [ ] Recording and export (SBS/BaseStation, Beast or JSON)
- [ ] Optional output to a network feed or local server (e.g. port 30003)
- [ ] Range and statistics view (max range, message rate history)

## Tooling
- [ ] CI build and unit tests (GitHub Actions)
- [ ] Release signing and versioning
