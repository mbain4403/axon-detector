# Axon Detector

A passive awareness app for Android. It listens for the Bluetooth Low Energy
signals that Axon police gear (body cameras, Tasers, Fleet vehicle units)
broadcasts openly, and tells you when that gear is nearby — a signal and a
rough range band, never an exact location. It also ships with a preloaded map
of 230 plate-reader cameras (200 of them Flock Safety) across New York's
Capital District, southern Adirondacks, and Mohawk Valley.

Built by Matthew Bain (Bain's) with Torque. Android only.

## Download

Grab the APK from the
[Releases page](https://github.com/mbain4403/axon-detector/releases) and
install it on your phone (Android will ask you to allow installing from your
browser/files app — that's normal for apps installed outside the Play Store).
Current version: **v1.3**.

## How detection works

Axon devices advertise over BLE and, unlike modern phones, do not randomize
their MAC addresses. The app matches advertisements against Axon's public
Bluetooth signatures:

| Signal | Value |
| --- | --- |
| MAC prefix (OUI) | `00:25:DF` |
| Bluetooth company ID | `0x034D` (TASER International) |
| Service UUIDs | `0xFC81`, `0xFE6B`, `0xFE6C` |
| Advertised names | containing `AXON` / `TASER` |

Listening is **receive-only**: the app never connects to, pairs with, or
transmits to any device. Signal strength is translated into a rough band
(Very close / Near / Mid-range / Far) — treat it as "somewhere around you,"
never a pinpoint. A hit can be an officer's body cam or Taser, a holster
Signal unit, or a Fleet vehicle system; not every officer or patrol car
broadcasts detectable gear.

## Privacy design

- Device addresses are in-memory match keys only: **never displayed, never
  written to disk, never transmitted.** Nothing is logged or stored. Hits
  clear themselves ~25 seconds after a signal is last seen.
- The Bluetooth permission is declared `neverForLocation` — the scan cannot
  be used to derive location. Location permission is requested separately,
  only for the camera map (your dot and distances), and the map works
  without it.
- The only network traffic is OpenStreetMap map tiles.
- Scanning runs while the app is open (foreground). Android restricts
  background and screen-off scanning; v1 doesn't fight that.

## Camera data

The preloaded camera set is OpenStreetMap data (© OpenStreetMap
contributors, ODbL), pulled October 30, 2026: 230 plate readers in 35 towns
(200 Flock-confirmed, 30 other plate readers). Coverage is regional, not
statewide. The dataset lives in `source/assets/www/cameras.js`.

## Build from source

No Gradle — a manual JDK + Android SDK pipeline (same as the author's other
apps):

1. Install JDK 17 and Android SDK platform 34 / build-tools 34.0.0, and
   Pillow (`pip install pillow`).
2. `python3 generate_icons.py` — generates the launcher icons into
   `source/res/` (icons are generated, not committed).
3. `source/build.sh` — compiles, dexes, zipaligns, and signs the APK to
   `AxonDetector.apk` at the project root. The script creates a local
   signing keystore under `build/` on first run — **keep yours private and
   never commit it**; updates must be signed with the same key to install
   over an existing copy.

Layout: detector logic in `source/java/com/bain/axondetector/MainActivity.java`
(native BLE bridge), UI and map in `source/assets/www/`.

## Version notes

- **v1.3** — fixed a crash where tapping "Enable Bluetooth access" looped the
  permission call on itself (stack overflow) before any prompt could appear.
- **v1.2** — hardened scan paths; on-screen crash reporting.
- **v1.1** — permission flow cleanup (Bluetooth-only ask, separate location
  ask for the map).
- **v1.0** — first build: detector + preloaded Flock camera map.
