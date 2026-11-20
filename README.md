# Instagram Reel Collector v3 — visible Reel URL detection

Fixes **Saved: 0** caused by `WebView.getUrl()` staying unchanged while Instagram reels advance.

## How it works
- Auto swipe every 3 seconds while open.
- Every ~1.2s, JavaScript checks the **visible Reel**, looking for its permalink via the current page URL, enclosing video link, unique link in the video's container, or one clearly dominant on-screen reel link.
- Saves **only a reasonably identifiable current Reel** to local private preferences, avoiding duplicates.
- `Detect:` in header reports `page-url`, `video-anchor`, `video-container`, `visible-link`, `no-permalink`, `no-video` or error.
- Copy All or Export TXT via Android file picker.
- `Save URL` also attempts to detect current visible Reel.
- Compatible with Android 6+ (Redmi 9A typically uses Android 10), with an up-to-date Android System WebView.

## Limitations
**Not guaranteed to collect every Reel.** Instagram can render Reel content without exposing a unique permalink in the DOM. In that case the app shows `no-permalink` and skips it rather than inventing a URL or collecting unrelated offscreen suggestions. Instagram may also block or change embedded WebView behavior. If needed use Instagram Share > Copy link (and Save URL cannot recover an absent link). No Instagram private API or login interception. 3-second scrolling does not avoid platform rate limits or rules.

## Build APK using GitHub Actions
Upload **the contents** of this zip (including `.github/workflows/build-apk.yml`) into repo root. Commit, Actions > Build Android APK > latest successful run > Artifacts > `InstagramReelCollector-v3-APK`, unzip and install `app-debug.apk`.

GitHub Actions uses JDK 17 and Gradle 8.9 for Android Gradle Plugin 8.7.3. Not compiled or device-tested here.
