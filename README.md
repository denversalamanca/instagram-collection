# Instagram Reel Collector v6 — stable pacing

Android Java WebView project (minSdk 23, suitable for Redmi 9A on Android 10).

## Automatic cycle
1. Tap Share.
2. Wait **1 second**, then tap Copy Link.
3. Wait **1 second** for clipboard, then attempt to dismiss Share.
4. Wait **1 second** for dismiss verification.
5. Wait **2 seconds**, then swipe to next Reel.
6. Wait **2 seconds** for the next Reel before the next Share attempt.

A single sequential cycle is used. DOM link probes are disabled during Auto mode and reduced to once every four seconds when idle, to reduce WebView load on low-memory devices. A WebView renderer failure is handled by recreating the activity instead of allowing the process to crash, when Android reports it. This is best-effort and does not guarantee recovery from every crash.

**Caveat:** Instagram may use a native share sheet or block scripted UI interactions, so not every share/copy operation or reel URL is guaranteed. An app cannot guarantee that a 1–3 second delay avoids Instagram automation checks.

## GitHub
Upload/replace the files in the same paths of `denversalamanca/instagram-collection`. Commit changes and open Actions → Build Android APK. Download the APK artifact if the build succeeds. The source has not been compiled or tested on a phone in this environment.

If the app crashes again, capture Android Logcat or a `adb logcat -d -b crash` report to identify the root cause.
