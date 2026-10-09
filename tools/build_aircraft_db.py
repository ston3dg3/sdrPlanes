#!/usr/bin/env python3
"""Builds app/src/main/assets/aircraft.bin.gz, the offline aircraft database.

Sources (downloaded unless given as files):
  - aircraft.csv.gz from wiedehopf/tar1090-db (built from the Mictronics database):
    icao;registration;type;flags;description;year;owner;...
  - html/flags.js from wiedehopf/tar1090: ICAO 24-bit address blocks per country
    (Annex 10 Vol III allocation table).

Usage:  python tools/build_aircraft_db.py [--csv aircraft.csv.gz] [--ranges flags.js]

The Android build stores it un-gzipped as assets/aircraft.bin (deflated inside the APK);
the app copies it once into private storage and memory-maps it.
Format (big-endian, gzip-compressed), read by com.sdrplanes.db.AircraftDb:
  "SPAC" u16 version
  str  source/date note                       (str = u8 length + UTF-8)
  u16  nTypes, str * nTypes                   ICAO type designators, index 0 = ""
  u16  nDescs, str * nDescs                   model descriptions,    index 0 = ""
  u16  nRanges, (u24 start, u24 end, str country, 2 bytes ISO code) * nRanges
  u32  nRecords, records sorted by address:
       u24 icao, u16 type, u16 desc, u8 flags, u8 year-1900 (0 = unknown), 10 bytes registration (NUL-padded)
  flags: bit0 military, bit1 interesting, bit2 PIA, bit3 LADD
"""
import argparse
import datetime
import gzip
import io
import re
import struct
import urllib.request

CSV_URL = "https://github.com/wiedehopf/tar1090-db/raw/refs/heads/csv/aircraft.csv.gz"
RANGES_URL = "https://raw.githubusercontent.com/wiedehopf/tar1090/master/html/flags.js"
OUT = "app/src/main/assets/aircraft.bin.gz"
REG_LEN = 10
VERSION = 1


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def s(text):
    b = text.encode("utf-8")[:255]
    return struct.pack(">B", len(b)) + b


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv")
    ap.add_argument("--ranges")
    ap.add_argument("--out", default=OUT)
    a = ap.parse_args()

    csv_gz = open(a.csv, "rb").read() if a.csv else fetch(CSV_URL)
    ranges_js = (open(a.ranges, encoding="utf-8").read() if a.ranges else fetch(RANGES_URL).decode("utf-8"))

    types, descs = {"": 0}, {"": 0}
    records = []
    for line in io.TextIOWrapper(gzip.GzipFile(fileobj=io.BytesIO(csv_gz)), encoding="utf-8"):
        f = line.rstrip("\n").split(";")
        if len(f) < 6:
            continue
        icao, reg, typ, flags, desc, year = f[:6]
        if not (reg or typ):
            continue
        try:
            addr = int(icao, 16)
        except ValueError:
            continue
        fl = 0
        for bit, ch in enumerate(flags[:4]):
            if ch == "1":
                fl |= 1 << bit
        y = int(year) - 1900 if year.isdigit() and 1900 < int(year) < 2155 else 0
        ti = types.setdefault(typ.strip(), len(types))
        di = descs.setdefault(desc.strip(), len(descs))
        rb = reg.strip().encode("ascii", "replace")[:REG_LEN].ljust(REG_LEN, b"\0")
        records.append((addr, ti, di, fl, y, rb))
    records.sort()
    assert len(types) < 65536 and len(descs) < 65536

    ranges = []
    for m in re.finditer(r"start:\s*0x([0-9A-Fa-f]+),\s*end:\s*0x([0-9A-Fa-f]+),\s*country:\s*\"([^\"]*)\",\s*country_code:\s*\"([^\"]*)\"", ranges_js):
        ranges.append((int(m.group(1), 16), int(m.group(2), 16), m.group(3), m.group(4)))
    ranges.sort()

    out = bytearray(b"SPAC" + struct.pack(">H", VERSION))
    out += s("tar1090-db (Mictronics) %s" % datetime.date.today().isoformat())
    for table in (types, descs):
        out += struct.pack(">H", len(table))
        for text, _ in sorted(table.items(), key=lambda kv: kv[1]):
            out += s(text)
    out += struct.pack(">H", len(ranges))
    for start, end, country, cc in ranges:
        out += start.to_bytes(3, "big") + end.to_bytes(3, "big") + s(country) + cc.upper().encode("ascii")[:2].ljust(2, b"?")
    out += struct.pack(">I", len(records))
    for addr, ti, di, fl, y, rb in records:
        out += addr.to_bytes(3, "big") + struct.pack(">HHBB", ti, di, fl, y) + rb

    with gzip.GzipFile(a.out, "wb", compresslevel=9, mtime=0) as fh:
        fh.write(out)
    print("%s: %d aircraft, %d types, %d descriptions, %d country ranges, %.1f MB"
          % (a.out, len(records), len(types), len(descs), len(ranges), len(out) / 1e6))


if __name__ == "__main__":
    main()
