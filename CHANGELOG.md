# Changelog

All notable changes to Light Control for iGPSPORT are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **OFF stays off.** When you switch the light off from the Karoo, it no longer turns itself back
  on when you move it. The light's **Auto sleep** is paused while the light is off, so moving it
  can't wake it. The Karoo switches Auto sleep back on as soon as you pick a mode, or when it sees
  you switch the light on with its own button. While it is paused, the app page says so under the
  **Auto sleep** switch. If you change that switch yourself, your choice is kept.
  - While the light is off like this, it stays in Bluetooth standby instead of sleeping, which uses
    a little battery. If you use the light without the Karoo in the meantime, Auto sleep stays off
    until the Karoo connects to it again.

## [0.2.0] - 2026-09-26

### Highlights

- **Custom light-mode editor.** Change your light's custom mode from the Karoo: steady or flashing,
  how bright, and how it flashes. No phone app needed.

### Added

- **Customise light modes** screen, opened from a button on the app page:
  - **Pattern:** Steady or Flash, whichever your light offers for the custom mode.
  - **Brightness:** 5–100 %.
  - **Flash cycle:** 1–4 s.
  - **Lighting time:** 10–50 % of each cycle.
  - Changes go to the light as soon as you release a slider or pick a pattern. There is no Save
    button.
  - **Show on light** switches the light to the custom mode, so you can see your changes.
  - **Restore original** puts the custom mode back to how it was the first time you opened the
    editor.
- **Custom modes on the ride field.** The field shows the custom mode by its setting, e.g.
  `C1 17%` for steady or `C1 FLASH` for flashing. The compact field shows `C1`.
- The "When a ride starts, turn the light on in …" list names the custom mode by its setting too.

### Changed

- **SOLID and FLASH follow the custom mode's pattern.** A steady custom mode is one of SOLID's
  levels, and a flashing one is one of FLASH's. Tapping SOLID never lands on a flashing custom mode.
- The **Light: open controls** button action always opens the main controls page, even if you left
  the editor open.

### Notes

- Custom-mode settings are stored in the light itself and survive switching it off. They also show
  up in the iGPSPORT phone app.
- The ranges match the iGPSPORT app's. The light accepts any value without checking, so the app
  keeps you inside them. Brightness stops at 5 %, so a custom mode can never be set completely dark
  by accident.
- Tested on a VS1200S, which has one custom mode with steady and flash patterns.

### Upgrading

- **On the Karoo:** long-press the app in the extension list → Update.
- **USB:** `adb install -r igpsport-karoo.apk`.
- Either way your pairing, fields and settings are kept.

## [0.1.0] - 2026-09-26

First public release.

### Added

- **Ride-screen field in 3 sizes:** regular, slim and compact.
  - **SOLID:** tap to cycle the steady levels.
  - **FLASH:** tap to cycle the flash levels.
  - **AUTO:** shows the mode it runs, plus `DIMMED` when the light has dimmed itself.
  - **OFF.**
  - Battery and run time in the footer.
- **App page** with the same controls, plus switches for the light's own features: Auto light, Auto
  sleep, Auto dim when stopped, Low-battery saving.
- **Ride automation:** pick a mode to switch to when a ride starts, and optionally turn the light
  off when the ride ends.
- **Hardware / bonus button actions:** next mode, solid, flash, auto, light off, open controls.
- **Low-battery alerts** at 20 % and 10 %.
- **Auto-reconnect** after the light's motion sleep, and tap-to-retry on the field.
- In-place updates from the Karoo's extension list.

[Unreleased]: https://github.com/tiagodias00/igpsport-karoo/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/tiagodias00/igpsport-karoo/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/tiagodias00/igpsport-karoo/releases/tag/v0.1.0
