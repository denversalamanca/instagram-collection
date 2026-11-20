# Instagram Reel Collector v5 — Share → Copy → Close → Wait → Scroll

Sequential cycle: find Share, tap Copy Link, validate real Reel permalink in clipboard, close the Share dialog, wait 1 second, swipe upward to next Reel, then repeat. No overlapping timers. Stops if Copy link cannot be found or Share remains open.

**Limitations:** Instagram WebView layout changes may prevent this automation; native sharing dialogs may not close automatically. This project cannot guarantee every Reel link is copied. Manual Share → Copy link remains supported.

## Build APK
Upload project files to GitHub preserving folder structure. GitHub Actions workflow produces a debug APK artifact when successful.
