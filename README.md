# Instagram Reel Collector v7 — Auto-Retry after 3 seconds

This version modifies the v6 Android Java WebView app.

## What is fixed

- **Copy link not found** and other recoverable Share/Copy failures no longer immediately leave Auto OFF.
- The app displays **Auto: RETRY in 3s**, attempts to dismiss a leftover HTML Share dialog, and programmatically resumes its own Auto Mode after 3 seconds.
- Maximum **3 consecutive retries**; after that it stops and asks for manual Share > Copy link. No infinite rapid restart loop.
- **Stop** / **Cancel Retry** cancels queued restarts immediately.
- After a valid copied Reel link is detected, the retry counter resets.
- Existing Share → wait 1s → Copy → wait 1s → close → wait 2s → swipe → wait 2s workflow is retained.
- Saved links remain locally stored and can be exported as TXT.
- Minimum Android API 23 (Android 6), targeting Redmi 9A Android 10 compatibility.

## Important limitations

Screenshot showing `Auto: OFF` with `Copy link not found` is evidence of an intentional stop on an in-app failure, **not proof of an Android process crash**.

This 3-second recovery works ONLY while the app's Activity/process remains alive, visible and WebView responds. After a true fatal app/process crash, no in-process handler can press Start; Android limits background activity launches. A separate logcat trace is needed to debug genuine crashes. If Instagram hides its Copy link control, it may still require manual interaction.

There is no guarantee that 3-second spacing avoids platform rate limits or policy restrictions. Never manufacture Reel URLs; this app saves only actual detected/copied Reel permalinks. Do not use the app for collection that violates platform rules.

## GitHub Actions build

Upload the **contents** of this folder to the GitHub repository root, overwriting old files (do not nest inside an extra `InstagramReelCollector_v7_...` directory). Include `.github/workflows/build-apk.yml`. Commit and open Actions → Build Android APK. On success download artifact `InstagramReelCollector-v7-AutoRetry-APK`. This project is source only, not a verified compiled APK.
