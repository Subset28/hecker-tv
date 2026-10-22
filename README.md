# Hecker TV

A custom home screen for Android TV boxes (built and tested on a Sony BRAVIA with Android TV).

- Clock, date and weather
- App grid: pick your apps, reorder with a hold-and-move, hide the ones you don't want
- Optional "Continue watching" row from your own Plex server (off by default)
- Built-in APK installer and app remover

## Plex (optional)

Create `/sdcard/tvhome/plex.json` on the TV:

```json
{"url": "http://192.168.1.50:32400", "token": "YOUR_PLEX_TOKEN"}
```

Then turn on Plex in the Customize panel. The launcher needs "All files access"
(Settings > Apps > Hecker TV > Special app access) to read this file.

Plain HTTP is only used for private (home-network) addresses. The launcher refuses
to send your token to any public host.

## Build

Requires the Android build tools (aapt2, d8, zipalign, apksigner, API 34 platform),
JDK 17, and your own signing key. Create a key first:

    keytool -genkeypair -keystore tvhome-release.keystore -alias tvhome -keyalg RSA -keysize 2048 -validity 10000

`build.ps1` expects these folders next to the repo (see the paths at the top of the script):
`jdk\`, `android-sdk\`, and `launcher-signing\` (keystore and `keystore-password.txt`).
Adjust the paths if your layout differs. Output: `build\tvhome.apk`.

## Test

- `tests\fake_plex.py` runs a local fake Plex server for testing.
- `tests\smoke.ps1` installs the APK on an Android emulator and checks it starts and moves focus.

## Notes

- The home background image is from Unsplash. Other artwork is not included, so add your own in `assets\`
  and list it in `assets\backdrops.json`.
- No license yet.
