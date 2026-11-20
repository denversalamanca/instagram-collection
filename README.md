# Instagram Reel Link Collector v2 — Auto Scroll

Android Java WebView project (minimum Android 6, API 23, including Redmi 9A on Android 10).

## Features
- Instagram mobile website inside the app.
- Start/Pause auto swipe every **3 seconds**, gesture duration ~420ms.
- Auto scrolling happens only while app is open and WebView is visible. Stops on pause/background or Saved view.
- Detects *main-frame navigations* to Instagram `/reel/SHORTCODE/` URL, deduplicates and persists locally.
- **Important limitation:** Instagram can show different Reels without changing WebView main URL. Those Reels **will not be captured** by this implementation. Auto-scrolling is not equivalent to reliable harvesting of Reel links. Embedded site login and swipe behavior also depend on Instagram's WebView support and site changes. Do not treat 3-second intervals as a guarantee of platform compliance or no rate limiting.
- Save URL, Copy All, export TXT using Android document picker. No video download.
- No background service, no Instagram password interception, no accessibility permissions.

## GitHub Actions
Upload the **contents** of this ZIP into the repository root (including `.github/workflows/build-apk.yml`) and commit on `main`. Actions > Build Android APK > choose latest run; on success download `InstagramReelCollector-AutoScroll-APK` from Artifacts. Unzip artifact, install `app-debug.apk` on Android. Build is not verified here because Android build tools are not installed in this environment.

## Requirements
Android Studio/Gradle plugin 8.7.3, Gradle 8.9, JDK 17, Android SDK 35. Redmi 9A Android 10 meets `minSdk 23`; free memory/WebView version and Instagram site compatibility can still affect results.
