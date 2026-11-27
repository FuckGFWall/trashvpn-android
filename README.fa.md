<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_art.png" width="112" alt="نماد TrashVPN">
</p>

<h1 align="center">TrashVPN</h1>
<p align="center"><a href="README.md">简体中文</a> · <a href="README.en.md">English</a> · فارسی</p>
<p align="center"><a href="https://github.com/FuckGFWall/trashvpn-android/releases">دانلود</a> · <a href="LICENSE">GPL-3.0</a></p>

**TrashVPN** یک کلاینت شخص ثالث Cloudflare WARP برای اندروید است که از هستهٔ Mihomo استفاده می‌کند و از ثبت‌نام خودکار، انتخاب گره و خروجی گرفتن از گره‌ها پشتیبانی می‌کند.

از **اندروید ۸٫۰+** پشتیبانی می‌شود.

## امکانات

- **پروتکل‌های متنوع:** WireGuard (WG)، AmneziaWG (AWG) و MASQUE (HTTP/3).
- **ثبت‌نام و انتخاب گره به‌صورت خودکار:** ثبت هویت WARP، غربال کردن گره‌های در دسترس و امکان انتخاب دستی گره‌ها و بررسی تأخیر.
- **انتخاب خودکار گره پایدار:** اسکن، VPN سیستمی را فعال نمی‌کند. یک هستهٔ Mihomo غربال اولیهٔ هم‌زمان و آزمون‌های تکراری HTTPS را انجام می‌دهد. نرخ موفقیت و پایداری بر صدک ۹۰ و میانگین تأخیر اولویت دارند. پس از اتصال، تونل جداگانه بررسی می‌شود و در صورت نیاز گره بعدی امتحان می‌شود.
- **گزینش MASQUE (H3):** ابتدا گره‌های تأییدشده و دروازه‌های شناخته‌شده آزموده می‌شوند، سپس از دو زیرشبکهٔ IPv4 به‌نوبت نمونه‌گیری می‌شود. می‌توانید طرح متوقف‌شده را ادامه دهید، یا با نگه‌داشتن «ادامهٔ گزینش»، «گزینش دوباره» را برای نمونه‌گیری تازه انتخاب کنید.
- **خروجی گره‌ها:** صادر کردن گره‌های معتبر ذخیره‌شده برای کلاینت‌های سازگار.
- **تنظیمات اتصال:** پشتیبانی از تنظیم شدت انتخاب گره، MTU و DNS.
- **چندزبانه:** چینی ساده‌شده، چینی سنتی، انگلیسی و فارسی.

## نصب و استفاده

1. از بخش [Releases](https://github.com/FuckGFWall/trashvpn-android/releases) یا [وبگاه رسمی برنامه](https://itmanager.top/download.html) فایل APK را دانلود کنید.
2. برنامه را باز کنید و منتظر بمانید تا ثبت‌نام و انتخاب گره به‌صورت خودکار انجام شود.
3. یک گره انتخاب کنید و اتصال را برقرار کنید؛ هنگام اتصال نخست، در اعلان اندروید اجازهٔ اتصال VPN را بدهید.

## توسعه و ساخت

نیاز به JDK 17 و Android SDK Platform 36 دارید. پروژه را در Android Studio باز کنید یا در ویندوز دستورهای زیر را اجرا کنید:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\fetch-mihomo.ps1
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

این اسکریپت وابستگی موردنیاز Mihomo برای اندروید را دانلود و اعتبار SHA-256 آن را بررسی می‌کند. فایل APK دیباگ در `app/build/outputs/apk/debug/` ساخته می‌شود.

## سپاس‌گزاری

- از [Oblivion](https://github.com/bepass-org/oblivion) به دلیل ایدهٔ ثبت‌نام سریع WARP.
- از [TrashVPN for iOS](https://github.com/FuckGFWall/trashvpn-ios) به دلیل مرجع طراحی رابط کاربری.
- از [TrashVPN for Windows](https://github.com/FuckGFWall/trashvpn-windows) به دلیل ایده‌های انتخاب گره.

## مجوز

این پروژه تحت مجوز [GNU General Public License v3.0](LICENSE) منتشر شده است.
