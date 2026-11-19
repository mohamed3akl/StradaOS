<div align="center">
<img src="fastlane/metadata/android/en-US/images/featureGraphic.png" alt="StradaOS">
<h1>StradaOS</h1>
<b>A new life for your car's Android screen</b>
<br><br>
<a href="https://github.com/lordnordic-debug/StradaOS/releases"><img src="https://img.shields.io/github/v/release/lordnordic-debug/StradaOS?color=FE3211&label=version"></a>
<a href="https://github.com/lordnordic-debug/StradaOS/releases"><img src="https://img.shields.io/github/downloads/lordnordic-debug/StradaOS/total?color=FE3211&label=downloads"></a>
<img src="https://img.shields.io/badge/Android-4.1%2B-3DDC84?logo=android&logoColor=white">
<a href="LICENSE"><img src="https://img.shields.io/github/license/lordnordic-debug/StradaOS?label=license"></a>
<br><br>
<a href="https://github.com/lordnordic-debug/StradaOS/releases/latest"><img src="https://raw.githubusercontent.com/NeoApplications/Neo-Backup/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" width="200" alt="Get it on GitHub"></a>
<br>
<b>🌐 <a href="https://lordnordic-debug.github.io/StradaOS/">lordnordic-debug.github.io/StradaOS</a></b> · <a href="#العربية">العربية</a>
</div>

---

**StradaOS** is a **free, ad-free, open-source** launcher (home screen) for Android car head units. It installs like any other app, **without flashing or touching the system**, and if you don't like it you remove it and everything stays as it was. It was made to revive head units that say *"Android 6"* but actually run Android 4.x, and it works on modern screens too.

<p align="center">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="49%">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="49%">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="49%">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="49%">
</p>

## ✨ Features

**On the home screen**
- 🗺️ **Live and offline map:** download your country's map from the app (free, from OpenStreetMap) and use it without internet. The arrow moves smoothly with the GPS.
- 🧭 **Turn-by-turn navigation:** search for a place and get guided with the next turn, distance and arrival time. Optionally add your own HERE API key for HERE search and traffic-aware routes.
- 📲 **Android Auto and CarPlay** in one tap: opens your screen's app or [Open Headunit](https://github.com/andreknieriem/headunit-revived) over USB.
- 🎵 **Your music:** title, artist and time of what plays over Bluetooth, with big controls. With Android Auto, the album art too. A built-in player for music on USB, SD card or internal storage.
- ⏱️ **Speedometer**, weather, clock and an **oil change reminder**.
- 🚗 **A photo of your vehicle**, with the background removed by StradaOS.

**Design your home screen**
- Player on the left or right, vehicle card shown or hidden, foldable Android Auto and CarPlay buttons.
- **Normal, compact or side** button bar (with a vertical player for wide screens like 1280×480).
- **Light, dark or automatic** mode and 10 colors.

**Your way**
- Each bar button opens the app or function you want. Hide the apps you don't use.
- **English and Arabic**, km/h or mph.
- Update notices with a QR code.

👉 What's new in each version: [Releases](https://github.com/lordnordic-debug/StradaOS/releases).

## 📥 Installation

1. Download **`StradaOS-x.y.z.apk`** from [Releases](https://github.com/lordnordic-debug/StradaOS/releases/latest) and copy it to the screen (if you use a USB drive, move it to internal storage first).
2. Turn on **Settings → Security → Unknown sources** and install it.
3. Press **Home** and choose **StradaOS → Always**. A wizard guides you through the rest.

**Android Auto on screens that don't have it:** install [Open Headunit](https://github.com/andreknieriem/headunit-revived/releases), connect your phone over USB and tap **Android Auto**. In *Settings → Music*, turn on the Android Auto album art.

## 📋 Compatibility

| Minimum | Tested |
|---|---|
| Android 4.1 (API 16), any landscape screen | Android 4.2 (Autochips AC8227, 1024×600), Android 10 and Android 17 |

Tried it on another screen? Tell us in an [issue](https://github.com/lordnordic-debug/StradaOS/issues).

## ❓ FAQ

<details><summary><b>The factory menu shows up at startup, not StradaOS</b></summary>
Set StradaOS as the home screen: <b>Settings → System → Home screen</b>. If your screen always goes back to its menu at startup, turn on <b>"Open StradaOS at startup"</b> there.
</details>

<details><summary><b>Why is there no album art with Bluetooth music?</b></summary>
Bluetooth between the phone and the head unit only sends the title, artist and time, not the image. Album art does show with Android Auto and with music apps installed on the screen.
</details>

<details><summary><b>Bluetooth music cuts out or doesn't play</b></summary>
Leave the Bluetooth app with <b>Home</b>, not Back. Check <b>Settings → Music → Bluetooth audio</b>; the help "Bluetooth music cuts out or doesn't show?" is there too.
</details>

<details><summary><b>The map doesn't load or my location doesn't show</b></summary>
The live map needs internet; the offline map doesn't. If it says "No GPS signal", check that the head unit's GPS antenna is connected. Long-press the map to see the diagnostics.
</details>

<details><summary><b>How do I stop using StradaOS?</b></summary>
In <b>Settings → System</b> you can go back to the factory menu or uninstall it. If the screen ever stays black, restart it (the <b>RES</b> button, or turn the car off and on).
</details>

## 🔒 Privacy

No accounts, ads or analytics. Your location is only used on the device. Details in [PRIVACY.md](PRIVACY.md).

## ☕ Support the project

StradaOS is and will stay free. If it helped you, give this repository a ⭐ or buy me a coffee at **[ko-fi.com/stradaos](https://ko-fi.com/stradaos)**. Reports and ideas are welcome: read [CONTRIBUTING.md](CONTRIBUTING.md).

<a href="https://github.com/lordnordic-debug/StradaOS/graphs/contributors"><img src="https://contrib.rocks/image?repo=lordnordic-debug/StradaOS"></a>

## 📄 License and credits

[GNU GPL v3.0](LICENSE). Maps © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), OpenTopoMap and Esri; offline map with a reader adapted from [mapsforge](https://github.com/mapsforge/mapsforge) (LGPL-3.0) and maps from [download.mapsforge.org](https://download.mapsforge.org/). Navigation search and routes: OpenStreetMap (Nominatim, Photon, OSRM) or, with your own key, HERE. Weather: [Open-Meteo](https://open-meteo.com). Android Auto with [Open Headunit](https://github.com/andreknieriem/headunit-revived) (GPL-3.0). QR from [Project Nayuki](https://github.com/nayuki/QR-Code-generator) (MIT). Icons: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0).

StradaOS is an independent project, not affiliated with Google, Apple or any manufacturer. *Android Auto* is a trademark of Google LLC and *CarPlay* of Apple Inc. Don't operate it while driving.

---

<div dir="rtl">

### العربية

**StradaOS** واجهة رئيسية (لانشر) **مجانية وبلا إعلانات ومفتوحة المصدر** لشاشات أندرويد في السيارات (أندرويد 4.1 وما بعده). تُثبَّت مثل أي تطبيق، **دون تفليش ودون المساس بالنظام**، وإن لم تعجبك تحذفها ويعود كل شيء كما كان.

- 🗺️ خريطة مباشرة و**خريطة بدون إنترنت** (نزّل خريطة بلدك من التطبيق)، وملاحة خطوة بخطوة.
- 📲 أزرار **Android Auto** و**CarPlay**.
- 🎵 موسيقاك عبر البلوتوث وAndroid Auto، ومشغّل مدمج لملفات USB.
- ⏱️ عدّاد سرعة، وطقس، وتذكير بتغيير الزيت، وصورة سيارتك.
- 🎨 شاشة رئيسية قابلة للتخصيص، وسمة فاتحة أو داكنة، و10 ألوان، **بالعربية والإنجليزية**.

**التثبيت:** نزّل ملف **`StradaOS-x.y.z.apk`** من [الإصدارات](https://github.com/lordnordic-debug/StradaOS/releases/latest)، وانسخه إلى الذاكرة الداخلية للشاشة، وفعّل **الإعدادات ← الأمان ← مصادر غير معروفة** ثم ثبّته. اضغط زر **الرئيسية** واختر **StradaOS ← دائمًا**.

[الموقع](https://lordnordic-debug.github.io/StradaOS/?lang=ar) · [التنزيل](https://github.com/lordnordic-debug/StradaOS/releases/latest) · [سياسة الخصوصية](PRIVACY.md)

</div>
