# iGPSPORT Light for Hammerhead Karoo

Control an iGPSPORT VS-series bike light (tested on the VS1200S) from a Hammerhead Karoo 3: switch
modes from a ride-screen field or the app page, see battery and run time, and automate the light
around your ride.

## Features

- **3 ride-screen field sizes** — regular, slim (two-line, smaller text) and compact (one row) —
  each with the same SOLID / FLASH / AUTO / OFF controls. Tap a button to switch, tap it again to
  cycle levels.
  - **SOLID** cycles HIGH → MID → LOW.
  - **FLASH** cycles FL HI ↔ FL LO.
  - **AUTO** turns on the light's own auto-brightness and shows the mode it's running, adding
    "DIMMED" when the light has dimmed itself.
  - **OFF** turns the light off.
- **Battery and run time**, shown on every control field. A separate "Light battery" field is also
  available if you just want a plain battery number.
- **Auto-reconnect** after the light's motion sleep (or a brief Bluetooth drop), with tap-to-retry
  on the field while it's searching.
- **An app page** with the same SOLID / FLASH / AUTO / OFF controls, plus switches for the light's
  own automatic features:
  - Auto light
  - Auto sleep
  - Auto dim when stopped
  - Low-battery saving
- **Hardware / bonus button actions**, mappable in Settings → Controls: next mode, solid (next
  level), flash (next level), auto, light off, open controls.
- **A mode to set when a ride starts** — pick a fixed mode, AUTO, or "don't change" — and an
  optional switch to **turn the light off when the ride ends**.
- **Low-battery alerts** at 20% and 10%.

## Compatibility

- **Tested:** Karoo 3, with an iGPSPORT VS1200S.
- **Probably works:** other iGPSPORT VS/TL lights that use the same BLE protocol (untested).
- **Needs** a Karoo OS with extension support: karoo-ext 1.1.9, KOS 1.634.2440 or newer.

## Install, option A: Hammerhead Companion app (recommended, no cable)

Follow Hammerhead's official sideload steps:

1. Put your Karoo on Wi-Fi.
2. On your phone, open the repo's [Releases page](https://github.com/tiagodias00/igpsport-karoo/releases/latest).
3. Long-press `igpsport-karoo.apk` → Share → Hammerhead Companion.
4. Tap Install on the Karoo.

This works once the repo is public.

## Install, option B: USB with adb

1. On the Karoo, enable Developer Options (Settings → About → tap Build Number 7 times) and turn
   on USB debugging.
2. Run `adb install -r igpsport-karoo.apk`.

## First setup

1. Open "iGPSPORT Light" on the Karoo and allow Nearby devices.
2. Close the iGPSPORT phone app (or turn the phone's Bluetooth off) — the light only accepts one
   connection at a time.
3. Go to Sensors → Add sensor → VS1200S.
4. Add the "Light controls" field (regular, slim or compact) to a ride page.

## Updating

Long-press the app in the Karoo's extension list → Update. This uses the release's
`manifest.json`.

## Tips and troubleshooting

- **A red cross in Sensors:** shake the light — it sleeps when it's been still — then tap Retry.
- **The field says "Searching… tap to retry":** tap it.
- **Auto light:** the light never tells the Karoo when it switches itself off in daylight, so the
  field can't show that; a note on the app page explains it instead.
- **No "turn off with bike computer" switch:** the light's own version of this did not reliably
  switch the light off on a Karoo Bluetooth drop, so it's left as-is on the light; use "Turn off
  the light when the ride ends" instead.
- **No speed-based brightness:** it needs speed input that only iGPSPORT computers provide, so
  it's left as a light-only setting, unused by this extension.

## Privacy

No internet use, no data collection.

## Build from source

Requires JDK 17 and Android SDK 35.

```
./gradlew test :app:assembleDebug
```

`tools/probe` has a small Python/BLE tool used to reverse-engineer the light's protocol from a PC;
see its own `probe.py` for usage.

## Credits

- Tiago Dias ([github.com/tiagodias00](https://github.com/tiagodias00))
- Protocol research by [cparfait/Bike-Light-Control](https://github.com/cparfait/Bike-Light-Control) (MIT)
- [karoo-ext](https://github.com/hammerheadnav/karoo-ext) by Hammerhead

## Disclaimer

Not affiliated with or endorsed by iGPSPORT or Hammerhead. Use at your own risk.

## License

MIT — see [LICENSE](LICENSE).
