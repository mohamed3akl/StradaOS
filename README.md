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

**🗺️ Map**
- **Live map** on the home screen (OpenStreetMap, OpenTopoMap or Esri), with adjustable brightness, **north up or turning with you**, and a car arrow that moves smoothly between GPS readings.
- **Offline map:** download your country's map from the app (free, from OpenStreetMap / mapsforge). Pause, resume or cancel the download; it continues after a signal drop or turning the car off. You can also copy a `.map` file to USB or internal storage, and delete maps to free space.
- The map says why it isn't showing ("No location permission", "Location off", "No GPS signal") and takes you to fix it. Long-press it for map diagnostics.
- On slow screens the live map can be turned off (the speedometer keeps working).

**🧭 Navigation**
- **Turn-by-turn guidance inside StradaOS:** next turn with an arrow, distance, remaining time and arrival time, with automatic re-routing when you leave the route.
- **Move around the map with your fingers:** pinch to zoom in and out, drag to move the map (a button brings it back to your car), and double-tap to zoom in.
- **Drop a pin to navigate:** press and hold any point on the map to drop a pin there, then tap **Go**. Hold another point to move the pin.
- **See the place first:** search, and the result is shown on the map with its name and distance; tap **Go** to start, or Back to return to the results.
- **HERE search and routes** (optional, with your own HERE API key in *Settings → Map & weather*): better search and routes that know live traffic. Without a key, OpenStreetMap (Nominatim, Photon, OSRM) is used.
- **Traffic on your route:** slow stretches orange, very slow ones red, and the delay in the bottom bar (for example "+6 min traffic"), refreshed every 5 minutes while driving.
- HERE works on Android 4.x screens too, thanks to a modern encryption library used only for HERE.
- Or open your favorite navigation app (Waze, Maps, OsmAnd…) from the map button.

**📲 Android Auto and CarPlay**
- One tap opens your screen's app or [Open Headunit](https://github.com/andreknieriem/headunit-revived) over USB (bundled; StradaOS can install it for you).
- With Android Auto, the album art shows in the home player. When Android Auto disconnects, the sound goes back to Bluetooth by itself.
- The buttons can fold away to show the whole map.

**🎵 Music and radio**
- **Now playing** from Bluetooth, Android Auto and music apps: title, artist and time with big controls. **Swipe the player** for next / previous song.
- ▶ opens or starts your favorite music app when nothing is playing (Android 5+), and moves Bluetooth audio to the speakers on screens that need it.
- **Built-in music player** for songs on USB, SD card or internal storage.
- **Radio mode** (in testing): on XY/PVE screens StradaOS runs the radio itself, without opening the radio app: on / off, previous / next saved station, the station in the player, and a **radio screen** with ‹ › to tune 0.1 MHz at a time (press and hold to search automatically), typing a frequency, big buttons for your saved stations (you can name them) and FM / AM. On other screens the buttons control the factory radio app.

**🚗 Your vehicle**
- **Speedometer** in km/h or mph, **weather** for your city and the clock.
- **Oil change reminder:** enter the dashboard mileage once; StradaOS adds what you drive with the GPS, shows what's left and reminds you when it's due ("Done" or "Not yet"). You can correct the mileage or the last change any time.
- **A photo of your vehicle**, with the background removed by StradaOS.

**🎨 Design your home screen**
- Player on the left or right, vehicle card shown or hidden.
- **Normal, compact or side** button bar, with a vertical player for low wide screens like 1280×480, and a layout for wide screens like 1920×720.
- **Light, dark, automatic, by schedule or following the system** theme, and 10 colors.
- Wi-Fi and Bluetooth icons in the top bar.

**📱 Apps and buttons**
- Long-press any bottom-bar button to choose what it opens: any app or a StradaOS function (Radio, Bluetooth, Camera, Music, Navigation, Android Auto, CarPlay…), and rename it.
- App drawer with icons: **hide** apps you don't use, open app info or **uninstall** with a long press.
- Setup wizard that shows which app opens Radio, Bluetooth, Camera, Files and Android Auto on your screen.

**🌐 Language**
- **English and Arabic**, with Arabic **right-to-left**.

**🛠️ System and help**
- Detects when StradaOS isn't the home screen and fixes it with one button; **"Open StradaOS at startup"** for screens that always boot into the factory menu.
- **Leave StradaOS** safely: open the factory launcher, disable or uninstall StradaOS.
- **Compatibility list:** each feature, which Android version or brand it needs, and how it works on your screen.
- **Diagnostics:** export the player status, the last 200 events, Bluetooth info and the **crash log** (it also records freezes over 8 s), and **send them to your phone over Wi-Fi** with a QR code (iPhone too).
- **Update notices** with the changes, a download button and a QR code; downloads are checked against the official repository and signature. Store installs (F-Droid, IzzyOnDroid) update through the store.
- Test builds show their own build number (for example `2.17.0-prueba.152-eaac007`) in *Settings → About*.

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

[GNU GPL v3.0](LICENSE). Maps © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), OpenTopoMap and Esri; offline map with a reader adapted from [mapsforge](https://github.com/mapsforge/mapsforge) (LGPL-3.0) and maps from [download.mapsforge.org](https://download.mapsforge.org/). Navigation search and routes: OpenStreetMap (Nominatim, Photon, OSRM) or, with your own key, HERE (with traffic). Modern encryption for HERE on Android 4.x: [Conscrypt](https://github.com/google/conscrypt) (Apache 2.0). Weather: [Open-Meteo](https://open-meteo.com). Android Auto with [Open Headunit](https://github.com/andreknieriem/headunit-revived) (GPL-3.0). QR from [Project Nayuki](https://github.com/nayuki/QR-Code-generator) (MIT). Icons: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0).

StradaOS is an independent project, not affiliated with Google, Apple or any manufacturer. *Android Auto* is a trademark of Google LLC and *CarPlay* of Apple Inc. Don't operate it while driving.

---

<div dir="rtl">

### العربية

**StradaOS** واجهة رئيسية (لانشر) **مجانية وبلا إعلانات ومفتوحة المصدر** لشاشات أندرويد في السيارات (أندرويد 4.1 وما بعده). تُثبَّت مثل أي تطبيق، **دون تفليش ودون المساس بالنظام**، وإن لم تعجبك تحذفها ويعود كل شيء كما كان.

- 🗺️ **خريطة مباشرة** (OpenStreetMap أو OpenTopoMap أو Esri) تتجه شمالًا أو تدور مع اتجاهك، و**خريطة بدون إنترنت**: نزّل خريطة بلدك مجانًا من التطبيق، مع الإيقاف المؤقت والاستئناف وحذف الخرائط.
- 🧭 **ملاحة خطوة بخطوة داخل StradaOS**: ابحث عن مكان وشاهده على الخريطة أولًا ثم اضغط **انطلق**، مع إعادة حساب المسار تلقائيًا.
- 🚦 **بحث ومسارات HERE** (اختياري بمفتاح HERE الخاص بك) مع **الازدحام على مسارك**: المقاطع البطيئة بالبرتقالي والمزدحمة بالأحمر، ومدة التأخير في الشريط السفلي. وبدون مفتاح تُستخدم OpenStreetMap. تعمل HERE أيضًا على شاشات أندرويد 4.x.
- 📲 **Android Auto** و**CarPlay** بلمسة واحدة (مع Open Headunit عبر USB)، وصورة الألبوم مع Android Auto.
- 🎵 **ما يُشغَّل الآن** من البلوتوث وAndroid Auto وتطبيقات الموسيقى بأزرار كبيرة، واسحب المشغّل لتغيير الأغنية، و**مشغّل موسيقى مدمج** لملفات USB وبطاقة SD والذاكرة الداخلية.
- 📻 **وضع الراديو** (تجريبي): تشغيل وإيقاف والمحطة السابقة والتالية لراديو المصنع من الشاشة الرئيسية.
- ⏱️ **عدّاد سرعة** (كم/س أو ميل/س)، وطقس، وساعة، و**تذكير بتغيير الزيت** يحسب المسافة بالـGPS، و**صورة سيارتك** بعد إزالة خلفيتها.
- 🎨 شاشة رئيسية قابلة للتخصيص: المشغّل يسارًا أو يمينًا، وشريط أزرار عادي أو مضغوط أو جانبي، وسمة فاتحة أو داكنة أو تلقائية أو حسب جدول أو حسب النظام، و10 ألوان.
- 📱 اختر ما يفتحه كل زر في الشريط السفلي، وأخفِ التطبيقات التي لا تستخدمها أو أزل تثبيتها من درج التطبيقات.
- 🌐 **العربية والإنجليزية**، والعربية **من اليمين إلى اليسار**.
- 🛠️ قائمة توافق لكل ميزة، و**تشخيص** يضم سجل الأحداث والأعطال، و**إرساله إلى هاتفك عبر Wi-Fi** برمز QR (يعمل مع iPhone أيضًا)، وإشعارات التحديث مع رمز QR.

**التثبيت:** نزّل ملف **`StradaOS-x.y.z.apk`** من [الإصدارات](https://github.com/lordnordic-debug/StradaOS/releases/latest)، وانسخه إلى الذاكرة الداخلية للشاشة، وفعّل **الإعدادات ← الأمان ← مصادر غير معروفة** ثم ثبّته. اضغط زر **الرئيسية** واختر **StradaOS ← دائمًا**.

[الموقع](https://lordnordic-debug.github.io/StradaOS/?lang=ar) · [التنزيل](https://github.com/lordnordic-debug/StradaOS/releases/latest) · [سياسة الخصوصية](PRIVACY.md)

</div>
