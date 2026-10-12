# Developing Gameday

## Build and test

Install Python 3, JDK 17 and an Android SDK with build-tools, then run:

```bash
scripts/check.sh
```

It runs the tests and writes `dist/gameday-<version>.zip`.

## Try a build on a kiosk

Use **Plugin Manager > Developer Tools > Install from ZIP**. A plugin installed from a local ZIP can't be replaced by the
GitHub version, and the other way around: uninstall first to switch sources. Uninstalling deletes the plugin's settings.

`tools/ks_install_plugin.py` installs a ZIP and changes settings through the Remote Admin API
(`install <zip>`, `state`, `set key=value ...`). It reads `KS_ADMIN_URL` and `KS_ADMIN_AUTH` from the environment or
from a gitignored `.local/LOCAL.md`.

## How it is organized

- `Match` reads the TeamTracker attributes, `BugState` decides what to show for a match and the clock, and `Display`
  is the result. These are plain Java with no Android imports, so the rules are tested off-device against
  `fixtures/teamtracker/`.
- `BugOverlay` is the only Android view code. `GamedayPlugin` wires it to Kiosk Satellite.
- `sdk/` and `tools/` come from the Kiosk Satellite plugin template and aren't edited here.
