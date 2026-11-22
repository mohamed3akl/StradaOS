# How to contribute

Thanks for wanting to improve StradaOS! 🚗

## Reporting problems
Use the [issue](../../issues/new/choose) template. Most useful:
- The StradaOS version and the screen's **real Android version** (in *Settings → About*).
- Photos of the screen.
- The diagnostics (*Settings → System → Export diagnostics*).

## Languages

StradaOS is available in **English** and **Arabic**.

In the code, texts are written in Spanish and translated when shown. The translations live in `app/src/main/assets/i18n/en.txt` and `app/src/main/assets/i18n/ar.txt`:

1. Each line is `spanish<TAB>translation`. **Only change the part after the tab**; keep the Spanish part exactly as it is (it is the key).
2. Keep the placeholders: `{}` (a value such as a number or a name goes there), `{z}/{x}/{y}`, `%s`, `%d` and `\n` (line break). Lines starting with `#` are comments.
3. Every new text in the code needs a line in **both** `en.txt` and `ar.txt`. A missing line shows the Spanish key on screen.

System labels (shown in Android's own lists) are in `app/src/main/res/values/strings.xml` (English, the default) and `app/src/main/res/values-ar/strings.xml`.

Arabic corrections are welcome: open a pull request with the changed lines in `ar.txt`.

## Building

Requirements: JDK 17 and the Android SDK.

```bash
./gradlew assembleDebug
# APK in app/build/outputs/apk/debug/
```

## Code rules
- **Android 4.1 (API 16) compatibility:** any newer API goes behind `if (Build.VERSION.SDK_INT >= …)`.
- Java 8 **without lambdas**, `java.util.Objects` or `StandardCharsets` (they don't exist on API 16–18).
- No Google Play Services dependencies, so the app can be on F-Droid.
- The interface is drawn in code on a 1024×600 canvas that is scaled (see `Main.place()`).

## Structure

```
app/src/main/java/com/stradaos/launcher/
  Main.java          home screen, settings, dialogs
  LiveMapView.java   live map (OSM tiles, cache, dark mode)
  OfflineMap.java    offline map reader (mapsforge .map files)
  Nav.java           turn-by-turn navigation (OpenStreetMap or HERE)
  NowPlaying.java    now playing (Android 4.x + player and Bluetooth broadcasts)
  MediaWatcher.java  now playing on Android 5+ (MediaSession)
  LocalPlayer.java   built-in music player (USB, SD card, internal storage)
  BtBridge.java      direct access to the factory Bluetooth app (Autochips/Clinkway)
  NotifArt.java      Android Auto album art from the Open Headunit notification
  Weather.java       weather (Open-Meteo)
  Updates.java       new version notices (GitHub Releases)
  CarPhoto.java      background removal and fitting of the vehicle photo
  Net.java           HTTPS with TLS 1.2 and current certificates on Android 4.x
  I18n.java          languages (English, Arabic)
app/src/hiddenapi/   Android 4.x hidden API stub (compile only, not in the APK)
app/src/legacy/      RcDisplay: the only class extending the hidden API (loaded by reflection)
```

## Version numbers

The version lives in `app/build.gradle` (`versionName` and `versionCode`).

**Which part to raise** (`MAJOR.MINOR.PATCH`, e.g. 2.17.0):
- **PATCH** (2.17.0 → 2.17.1): only fixes, nothing new for the user.
- **MINOR** (2.17.1 → 2.18.0): at least one new feature or a visible change.
- **MAJOR** (2.x → 3.0.0): a redesign or something that changes how people use StradaOS.
- **versionCode**: +1 on every published version, never reused or lowered.

**When**: raise the version once per published release (not per commit), together with `changelogs/X.Y.Z.md` and `fastlane/metadata/android/{en-US,ar}/changelogs/<versionCode>.txt`. Pushing a new `versionName` to `main` makes `release.yml` publish it.

**Test builds tell themselves apart automatically**: the debug build ("StradaOS Prueba") is named `<version>-prueba.<build>-<commit>`, for example `2.17.0-prueba.152-eaac007`:
- `<build>` is the number of commits, so a newer build always has a higher number.
- `<commit>` is the git commit it was built from.
- `-local` is added when it was built with changes not yet committed.

You can see it in *Settings → About* and at the top of `player.txt` in the diagnostics. Release builds keep the clean version (`2.17.0`).

## Publishing a version (maintainers)
1. Bump `versionCode` and `versionName` in `app/build.gradle`.
2. Write `changelogs/X.Y.Z.md` and `fastlane/metadata/android/*/changelogs/<versionCode>.txt`.
3. Commit and push to `main` (or push a `vX.Y.Z` tag).
4. GitHub Actions builds and signs the APK, creates the Release and updates `update.json`, the file the app uses to notify users.
