package com.manuminati.reelcollector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;
import android.view.ViewGroup;
import android.os.Build;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reel Collector v7: only saves real Instagram Reel links.
 * Automatic Share -> Copy link uses visible web controls, not private APIs.
 * Instagram may reject scripted / synthetic interaction. If so, manually
 * tapping Share -> Copy link while the app is open still collects via clipboard.
 */
public class MainActivity extends Activity {
    private static final int EXPORT_REQUEST = 42;
    // Explicit pacing: Share -> 1s -> Copy -> 1s -> Back -> 2s -> Swipe -> 2s -> Share.
    private static final long AFTER_SHARE_DELAY = 1000L;
    private static final long AFTER_COPY_DELAY = 1000L;
    private static final long AFTER_CLOSE_DELAY = 2000L;
    private static final long AFTER_SWIPE_DELAY = 2000L;
    private static final long DISMISS_VERIFY_DELAY = 1000L;
    // Recoverable UI errors: retry the Start Auto cycle after 3 seconds.
    // Hard-stop after 3 failures rather than repeatedly sending actions forever.
    private static final long AUTO_RETRY_DELAY = 3000L;
    private static final int MAX_AUTO_RETRIES = 3;
    private static final Pattern REEL_LINK = Pattern.compile(
        "https?://(?:(?:www|m)\\.)?instagram\\.com/(reel|share/reel)/([A-Za-z0-9_-]+)",
        Pattern.CASE_INSENSITIVE);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> links = new HashSet<>();
    private WebView browser;
    private TextView status;
    private LinearLayout savedList;
    private ScrollView listScroll;
    private Button autoButton;
    private ClipboardManager clipboard;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private boolean foreground = true;
    private boolean listVisible = false;
    private boolean autoScroll = false;
    // Remains true during the 3-second recovery countdown; unlike autoScroll.
    private boolean autoRequested = false;
    private int autoRetryCount = 0;
    private Runnable pendingAutoRetry;
    private boolean probePending = false;
    private boolean shareBusy = false;
    private int cycleToken = 0;
    private boolean sharePending = false;
    private String detectStatus = "waiting";
    private String shareStatus = "ready";
    private String clipboardBeforeAttempt = "";

    private final Runnable periodicProbe = new Runnable() {
        @Override public void run() {
            // Avoid DOM scans during the heavy Share/Copy/Swipe automation.
            if (foreground && browser != null && !listVisible && !autoScroll && !shareBusy) {
                collect(browser.getUrl());
                probeVisibleReel();
            }
            handler.postDelayed(this, 4000);
        }
    };

    /** Single sequential cycle; never overlap two Share sheets. */
    private final Runnable autoCycle = new Runnable() {
        @Override public void run() {
            if (!autoScroll || !foreground || listVisible || shareBusy) return;
            if (browser == null) { stopAuto(); return; }
            collect(browser.getUrl());
            attemptShareCopy(true);
        }
    };

    private void scheduleNextCycle(int token) {
        if (token != cycleToken || !autoScroll || !foreground || listVisible) return;
        handler.postDelayed(() -> {
            if (token == cycleToken && autoScroll && foreground && !listVisible) {
                shareStatus = "scrolling to next Reel";
                updateStatus();
                swipeUpSlowly();
                handler.postDelayed(() -> {
                    if (token == cycleToken && autoScroll && foreground && !listVisible) {
                        handler.post(autoCycle);
                    }
                }, AFTER_SWIPE_DELAY);
            }
        }, AFTER_CLOSE_DELAY);
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        links.addAll(getPreferences(MODE_PRIVATE).getStringSet("reels", Collections.emptySet()));
        clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipListener = () -> {
            if (foreground) handler.post(this::captureClipboard);
        };
        if (clipboard != null) clipboard.addPrimaryClipChangedListener(clipListener);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);

        status = new TextView(this);
        status.setTextSize(14f);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(14, 10, 14, 10);
        root.addView(status);

        LinearLayout first = horizontal(root);
        button(first, "Reels", () -> { showBrowser(); browser.loadUrl("https://www.instagram.com/reels/"); });
        button(first, "Try Share + Copy", () -> {
            showBrowser();
            attemptShareCopy(false);
        });
        button(first, "Saved", this::showList);

        LinearLayout second = horizontal(root);
        autoButton = button(second, "Start Auto", this::toggleAuto);
        button(second, "Stop", this::stopAuto);

        LinearLayout third = horizontal(root);
        button(third, "Paste Link", this::pasteLink);
        button(third, "Copy All", this::copyAll);
        button(third, "Export TXT", this::exportTxt);
        button(third, "Clear", () -> new AlertDialog.Builder(this)
            .setTitle("Delete all saved Reel links?")
            .setPositiveButton("Delete", (d, w) -> {
                links.clear(); saveLinks(); renderList();
            })
            .setNegativeButton("Cancel", null).show());

        browser = new WebView(this);
        browser.getSettings().setJavaScriptEnabled(true);
        browser.getSettings().setDomStorageEnabled(true);
        browser.getSettings().setMediaPlaybackRequiresUserGesture(false);
        browser.getSettings().setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // Android 8+: a low-memory WebView renderer can die independently.
                // Remove the dead instance and recreate the Activity instead of crashing.
                stopAuto();
                shareBusy = false;
                probePending = false;
                shareStatus = "WebView restarted after renderer stopped";
                updateStatus();
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) parent.removeView(view);
                if (browser == view) browser = null;
                view.destroy();
                handler.post(() -> { if (!isFinishing() && !isDestroyed()) recreate(); });
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                if (host.equals("instagram.com") || host.endsWith(".instagram.com")) {
                    collect(uri.toString());
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                catch (Exception ignored) { toast("Could not open link"); }
                return true;
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                collect(url);
                probeVisibleReel();
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                collect(url);
                probeVisibleReel();
            }
        });
        root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));

        listScroll = new ScrollView(this);
        listScroll.setVisibility(View.GONE);
        savedList = new LinearLayout(this);
        savedList.setOrientation(LinearLayout.VERTICAL);
        savedList.setPadding(16, 8, 16, 8);
        listScroll.addView(savedList);
        root.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        renderList();
        updateStatus();
        browser.loadUrl("https://www.instagram.com/reels/");
        handler.postDelayed(periodicProbe, 4000);
        receiveSharedText(getIntent());
    }

    private LinearLayout horizontal(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);
        return row;
    }
    private Button button(LinearLayout row, String title, Runnable callback) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(11f);
        b.setAllCaps(false);
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        b.setOnClickListener(v -> callback.run());
        return b;
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void updateStatus() {
        if (status != null) status.setText("Saved: " + links.size() +
            " | Auto: " + (autoScroll ? "ON / sequential" : (autoRequested ? "RETRY in 3s" : "OFF")) +
            "\nDetect: " + detectStatus + " | Share: " + shareStatus);
    }

    private void toggleAuto() {
        if (autoRequested) { stopAuto(); return; }
        showBrowser();
        autoRequested = true;
        autoRetryCount = 0;
        cycleToken++;
        autoScroll = true;
        autoButton.setText("Pause Auto Scroll");
        updateStatus();
        handler.removeCallbacks(autoCycle);
        handler.post(autoCycle);
        toast("Auto started. If Copy link fails, retry after 3 seconds (max 3).");
    }
    private void stopAuto() {
        autoRequested = false;
        autoScroll = false;
        cycleToken++;
        handler.removeCallbacks(autoCycle);
        if (pendingAutoRetry != null) {
            handler.removeCallbacks(pendingAutoRetry);
            pendingAutoRetry = null;
        }
        shareBusy = false;
        sharePending = false;
        if (autoButton != null) autoButton.setText("Start Auto");
        updateStatus();
    }

    /**
     * A failed Share/Copy lookup is an in-app stop, not necessarily an app crash.
     * Resume the workflow after 3 seconds only while this Activity is alive,
     * visible, and the user has not pressed Stop. Do not silently skip Reels.
     */
    private void autoRetryInThreeSeconds(String reason) {
        shareBusy = false;
        sharePending = false;
        if (!autoRequested || !foreground || browser == null || listVisible) {
            shareStatus = reason;
            updateStatus();
            return;
        }
        autoScroll = false;
        cycleToken++;
        handler.removeCallbacks(autoCycle);
        if (pendingAutoRetry != null) handler.removeCallbacks(pendingAutoRetry);
        if (autoRetryCount >= MAX_AUTO_RETRIES) {
            autoRequested = false;
            shareStatus = reason + " | 3 retries failed; tap Copy manually";
            if (autoButton != null) autoButton.setText("Start Auto");
            updateStatus();
            toast("Auto paused after 3 failed retries. Try Share > Copy link manually.");
            return;
        }
        autoRetryCount++;
        final int token = cycleToken;
        shareStatus = reason + " | retry " + autoRetryCount + "/3 in 3s";
        if (autoButton != null) autoButton.setText("Cancel Retry");
        updateStatus();
        pendingAutoRetry = () -> {
            pendingAutoRetry = null;
            if (token != cycleToken || !autoRequested || !foreground || listVisible || browser == null) return;
            // Clean up a possible leftover Share dialog before retrying Start Auto.
            final WebView active = browser;
            active.evaluateJavascript(DISMISS_SHARE, ignored -> {
                if (token != cycleToken || !autoRequested || !foreground || browser != active) return;
                handler.postDelayed(() -> {
                    if (token != cycleToken || !autoRequested || !foreground || browser != active) return;
                    active.evaluateJavascript("(function(){return !!document.querySelector('[role=\"dialog\"],dialog[open]')})()", value -> {
                        if (token != cycleToken || !autoRequested || !foreground || browser != active) return;
                        if ("true".equals(value)) {
                            // We must not swipe while Instagram's Share overlay is open.
                            autoRequested = false;
                            autoScroll = false;
                            shareStatus = "Share still open; close manually, then Start Auto";
                            if (autoButton != null) autoButton.setText("Start Auto");
                            updateStatus();
                            toast("Share is still open. Close it manually and press Start Auto.");
                            return;
                        }
                        autoScroll = true;
                        shareStatus = "Auto restarted after 3s; finding Share";
                        if (autoButton != null) autoButton.setText("Pause Auto Scroll");
                        updateStatus();
                        handler.post(autoCycle);
                    });
                }, 450L);
            });
        };
        handler.postDelayed(pendingAutoRetry, AUTO_RETRY_DELAY);
    }

    // Physical tap at a point in the WebView. Coordinates returned by JS are CSS pixels.
    private void tapCssPosition(double cssX, double cssY, double cssW, double cssH) {
        if (browser == null || cssW <= 0 || cssH <= 0 || browser.getWidth() <= 0) return;
        float x = (float) (cssX * browser.getWidth() / cssW);
        float y = (float) (cssY * browser.getHeight() / cssH);
        final float fx = Math.max(1, Math.min(browser.getWidth()-1, x));
        final float fy = Math.max(1, Math.min(browser.getHeight()-1, y));
        final long down = SystemClock.uptimeMillis();
        dispatchTouch(MotionEvent.ACTION_DOWN, fx, fy, down, down);
        handler.postDelayed(() -> dispatchTouch(MotionEvent.ACTION_UP, fx, fy, down, SystemClock.uptimeMillis()), 75);
    }
    private void dispatchTouch(int action, float x, float y, long down, long when) {
        if (browser == null) return;
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        try { browser.dispatchTouchEvent(e); } finally { e.recycle(); }
    }
    private void swipeUpSlowly() {
        if (browser == null || browser.getWidth() < 1 || browser.getHeight() < 150 || !browser.isShown()) return;
        final float x = browser.getWidth() * .5f;
        final float y0 = browser.getHeight() * .81f;
        final float y1 = browser.getHeight() * .20f;
        final long down = SystemClock.uptimeMillis();
        dispatchTouch(MotionEvent.ACTION_DOWN, x, y0, down, down);
        for (int i=1; i<=12; i++) {
            final int index = i;
            handler.postDelayed(() -> {
                if (!autoScroll || !foreground || listVisible) return;
                float y = y0 + (y1-y0)*index/12f;
                dispatchTouch(index==12 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE,
                    x, y, down, SystemClock.uptimeMillis());
            }, i*35L);
        }
    }

    /**
     * Finds the actual on-screen Share icon and the Copy link action in a native-looking
     * Instagram web dialog. This does NOT spoof an Instagram permalink.
     * If Instagram blocks the action or renders a native sheet, manual tapping is required.
     */
    private static final String FIND_SHARE = """
        (function(){
          try {
            const W=innerWidth,H=innerHeight;
            function visible(e){
              if(!e)return null;
              const r=e.getBoundingClientRect();
              if(r.width<3||r.height<3||r.right<=0||r.bottom<=0||r.left>=W||r.top>=H)return null;
              return {x:(r.left+r.right)/2,y:(r.top+r.bottom)/2};
            }
            function touchable(e){return e.closest('button,[role="button"],a') || e.parentElement || e;}
            const all = document.querySelectorAll('svg[aria-label],button[aria-label],[role="button"][aria-label],svg title');
            const options=[];
            for (const e of all) {
              const label=((e.getAttribute('aria-label')||e.textContent||'').trim());
              if (!/^(share|share post|share reel|send post|share to|send)$/i.test(label)) continue;
              const target=touchable(e.tagName.toLowerCase()==='title' ? e.parentElement : e);
              const p=visible(target);
              if(p)options.push({p,score:(p.x>W*.55?100:0)+p.y/H*20});
            }
            options.sort((a,b)=>b.score-a.score);
            const p=options.length?options[0].p:null;
            return JSON.stringify({found:!!p,x:p?p.x:0,y:p?p.y:0,w:W,h:H});
          }catch(e){return JSON.stringify({found:false,error:'share-js-error'});}
        })();
        """;

    private static final String FIND_COPY = """
        (function(){
          try{
            const W=innerWidth,H=innerHeight;
            function visible(e){
              if(!e)return null;
              const r=e.getBoundingClientRect();
              if(r.width<3||r.height<3||r.right<=0||r.bottom<=0||r.left>=W||r.top>=H)return null;
              return {x:(r.left+r.right)/2,y:(r.top+r.bottom)/2};
            }
            const all=document.querySelectorAll('button,[role="button"],[aria-label],span');
            for(const e of all){
              const label=(e.getAttribute('aria-label')||e.textContent||'').trim();
              if(!/^(copy link|copy reel link|copy url|copy post link)$/i.test(label))continue;
              const clickable=e.closest('button,[role="button"],a')||e.parentElement||e;
              const p=visible(clickable);
              if(p)return JSON.stringify({found:true,x:p.x,y:p.y,w:W,h:H});
            }
            return JSON.stringify({found:false});
          }catch(e){return JSON.stringify({found:false,error:'copy-js-error'});}
        })();
        """;

    private interface TapResult { void onResult(boolean success); }
    private void tapWebElement(String js, TapResult callback) {
        if (browser == null || listVisible || !foreground) { callback.onResult(false); return; }
        final WebView active = browser;
        active.evaluateJavascript(js, raw -> {
            if (browser != active || !foreground) { callback.onResult(false); return; }
            try {
                JSONObject obj = decodeJavascriptObject(raw);
                if (!obj.optBoolean("found", false)) { callback.onResult(false); return; }
                tapCssPosition(obj.getDouble("x"), obj.getDouble("y"), obj.getDouble("w"), obj.getDouble("h"));
                callback.onResult(true);
            } catch(Exception e) { callback.onResult(false); }
        });
    }
    private JSONObject decodeJavascriptObject(String raw) throws Exception {
        Object unwrapped = new JSONTokener(raw).nextValue();
        return new JSONObject(String.valueOf(unwrapped));
    }

    // Instagram can implement a Share sheet either as HTML or as a native dialog.
    // We dismiss the HTML sheet first. We do not call WebView.goBack() blindly,
    // because that can navigate away from Reels instead of closing the sheet.
    private static final String DISMISS_SHARE = """
        (function(){
          try {
            const dialog=document.querySelector('[role="dialog"],dialog[open]');
            if(!dialog)return JSON.stringify({open:false,closed:true});
            const buttons=dialog.querySelectorAll('button,[role="button"],[aria-label]');
            for(const el of buttons){
              const label=(el.getAttribute('aria-label')||el.textContent||'').trim();
              if(/^(close|dismiss|back|cancel|done|x)$/i.test(label)){
                el.click();return JSON.stringify({open:true,closed:true});
              }
            }
            const backdrop=document.elementFromPoint(8,Math.floor(innerHeight/2));
            if(backdrop && !dialog.contains(backdrop)){
              backdrop.click();return JSON.stringify({open:true,closed:true});
            }
            return JSON.stringify({open:true,closed:false});
          }catch(e){return JSON.stringify({open:true,closed:false});}
        })();
        """;

    private void closeShareThenContinue(int token, boolean automatic) {
        shareStatus = "closing Share";
        updateStatus();
        if (browser == null || !foreground) { if (automatic) stopAuto(); return; }
        browser.evaluateJavascript(DISMISS_SHARE, raw -> {
            boolean closed = false;
            try { closed = decodeJavascriptObject(raw).optBoolean("closed", false); }
            catch (Exception ignored) { }
            if (!closed) {
                // Escape can dismiss some web overlays without navigating away.
                if (browser == null) { if (automatic) stopAuto(); return; }
                long now = SystemClock.uptimeMillis();
                browser.dispatchKeyEvent(new android.view.KeyEvent(now, now,
                    android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ESCAPE, 0));
                browser.dispatchKeyEvent(new android.view.KeyEvent(now, now + 1,
                    android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ESCAPE, 0));
            }
            handler.postDelayed(() -> {
                if (automatic && (token != cycleToken || !autoScroll)) return;
                if (browser == null || !foreground) return;
                browser.evaluateJavascript("(function(){return !!document.querySelector('[role=\"dialog\"],dialog[open]')})()", open -> {
                    if ("true".equals(open)) {
                        shareStatus = "Share still open: close manually";
                        shareBusy = false;
                        sharePending = false;
                        if (automatic) autoRetryInThreeSeconds("Share still open");
                        else updateStatus();
                        return;
                    }
                    shareStatus = "Share closed; waiting 2s";
                    shareBusy = false;
                    sharePending = false;
                    updateStatus();
                    if (automatic) scheduleNextCycle(token);
                });
            }, DISMISS_VERIFY_DELAY);
        });
    }

    private void attemptShareCopy(boolean automatic) {
        if (browser == null || !foreground || listVisible || shareBusy) return;
        final int token = cycleToken;
        shareBusy = true;
        sharePending = true;
        clipboardBeforeAttempt = clipboardText();
        shareStatus = "finding Share";
        updateStatus();
        tapWebElement(FIND_SHARE, didTapShare -> {
            if (automatic && token != cycleToken) return;
            if (!didTapShare) {
                shareBusy = false;
                sharePending = false;
                shareStatus = "Share button unavailable";
                if (automatic) autoRetryInThreeSeconds("Share button unavailable");
                else updateStatus();
                return;
            }
            shareStatus = "Share tapped";
            updateStatus();
            handler.postDelayed(() -> tapWebElement(FIND_COPY, didTapCopy -> {
                if (automatic && token != cycleToken) return;
                if (!didTapCopy) {
                    shareBusy = false;
                    shareStatus = "Copy link not found";
                    if (automatic) autoRetryInThreeSeconds("Copy link not found");
                    else {
                        updateStatus();
                        toast("Copy link not found; tap it manually, then close Share.");
                    }
                    return;
                }
                shareStatus = "Copy link tapped";
                updateStatus();
                handler.postDelayed(() -> {
                    if (automatic && token != cycleToken) return;
                    String current = clipboardText();
                    if (!current.isEmpty() && !current.equals(clipboardBeforeAttempt)) collect(current);
                    // The clipboard may be unavailable, or Instagram may have blocked copying.
                    // Never advance automatically unless we have a valid Reel permalink.
                    if (current.isEmpty() || !REEL_LINK.matcher(current).find()) {
                        shareStatus = "No copied Reel URL";
                        shareBusy = false;
                        if (automatic) autoRetryInThreeSeconds("No copied Reel URL");
                        else updateStatus();
                        return;
                    }
                    // A valid copied link means this cycle succeeded; reset the retry budget.
                    autoRetryCount = 0;
                    closeShareThenContinue(token, automatic);
                }, AFTER_COPY_DELAY);
            }), AFTER_SHARE_DELAY);
        });
    }

    // Clipboard collection is restricted to times when this app is foregrounded.
    // It never saves unrelated clipboard text and does not ask for background access.
    private String clipboardText() {
        try {
            if (clipboard == null || !clipboard.hasPrimaryClip()) return "";
            ClipData data = clipboard.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) return "";
            CharSequence value = data.getItemAt(0).coerceToText(this);
            return value == null ? "" : value.toString();
        } catch (Exception ignored) { return ""; }
    }
    private void captureClipboard() {
        if (!foreground) return;
        String text = clipboardText();
        if (!text.isEmpty()) {
            int before = links.size();
            collect(text);
            if (links.size() > before) {
                shareStatus = "Link copied and SAVED";
                autoRetryCount = 0;
                sharePending = false;
                updateStatus();
                toast("Reel link saved: " + links.size());
            }
        }
    }

    // DOM probe: only on-screen Reel links, avoiding unrelated off-screen suggestions.
    private void probeVisibleReel() {
        if (browser == null || !foreground || listVisible || probePending) return;
        probePending = true;
        String js = """
            (function(){
              try{
                function clean(h){
                  if(!h)return null;
                  const u=new URL(h,location.href);
                  if(!['instagram.com','www.instagram.com','m.instagram.com'].includes(u.hostname))return null;
                  const parts=u.pathname.split('/').filter(Boolean);
                  if(parts[0]==='reel' && parts[1])return 'https://www.instagram.com/reel/'+parts[1]+'/';
                  if(parts[0]==='share'&&parts[1]==='reel'&&parts[2])
                     return 'https://www.instagram.com/share/reel/'+parts[2]+'/';
                  return null;
                }
                const loc=clean(location.href);
                if(loc)return JSON.stringify({url:loc,method:'page-url'});
                const W=innerWidth,H=innerHeight;
                function visible(el){
                  const r=el.getBoundingClientRect();
                  const overlapX=Math.max(0,Math.min(r.right,W)-Math.max(r.left,0));
                  const overlapY=Math.max(0,Math.min(r.bottom,H)-Math.max(r.top,0));
                  return overlapX*overlapY;
                }
                const videos=Array.from(document.querySelectorAll('video'))
                  .filter(v=>visible(v)>100)
                  .sort((a,b)=>(b.paused?0:1)-(a.paused?0:1)||visible(b)-visible(a));
                // Some Instagram variants place a canonical permalink in the video container.
                if(videos.length){
                  let n=videos[0];
                  for(let depth=0;depth<10&&n;depth++,n=n.parentElement){
                    if(n.tagName==='A'){
                      const link=clean(n.href);if(link)return JSON.stringify({url:link,method:'video-anchor'});
                    }
                    const found=Array.from(n.querySelectorAll('a[href]'))
                      .map(a=>clean(a.href)).filter(Boolean);
                    const unique=Array.from(new Set(found));
                    if(unique.length===1)return JSON.stringify({url:unique[0],method:'video-container'});
                  }
                }
                const visibleLinks=Array.from(document.querySelectorAll('a[href]'))
                  .map(a=>({link:clean(a.href),weight:visible(a)}))
                  .filter(v=>v.link&&v.weight>10)
                  .sort((a,b)=>b.weight-a.weight);
                if(visibleLinks.length===1)return JSON.stringify({url:visibleLinks[0].link,method:'visible-anchor'});
                return JSON.stringify({url:null,method:videos.length?'no-permalink':'no-video'});
              }catch(e){return JSON.stringify({url:null,method:'js-error'});}
            })();
            """;
        browser.evaluateJavascript(js, raw -> {
            probePending = false;
            try {
                JSONObject obj = decodeJavascriptObject(raw);
                String url = obj.optString("url", "");
                detectStatus = obj.optString("method", "unknown");
                if (url != null && !url.equals("null") && !url.isEmpty()) collect(url);
            } catch(Exception ignored) { detectStatus = "parse-error"; }
            updateStatus();
        });
    }

    private void collect(String text) {
        if (text == null) return;
        Matcher matcher = REEL_LINK.matcher(text);
        boolean changed = false;
        while (matcher.find()) {
            String kind = matcher.group(1).toLowerCase(Locale.ROOT);
            String id = matcher.group(2);
            String clean = "https://www.instagram.com/" + kind + "/" + id + "/";
            if (links.add(clean)) changed = true;
        }
        if (changed) { saveLinks(); renderList(); }
    }
    private void saveLinks() {
        getPreferences(MODE_PRIVATE).edit().putStringSet("reels", new HashSet<>(links)).apply();
        updateStatus();
    }
    private List<String> sortedLinks() {
        List<String> result = new ArrayList<>(links);
        Collections.sort(result);
        return result;
    }
    private String allLinks() { return android.text.TextUtils.join("\n", sortedLinks()); }
    private void renderList() {
        if (savedList == null) return;
        savedList.removeAllViews();
        for (String link : sortedLinks()) {
            TextView t = new TextView(this);
            t.setText(link);
            t.setTextSize(14);
            t.setPadding(0, 9, 0, 9);
            t.setTextIsSelectable(true);
            t.setOnClickListener(v -> {
                clipboard.setPrimaryClip(ClipData.newPlainText("Reel link", link));
                toast("Link copied");
            });
            savedList.addView(t);
        }
        updateStatus();
    }
    private void showList() {
        stopAuto();
        listVisible = true;
        browser.setVisibility(View.GONE);
        listScroll.setVisibility(View.VISIBLE);
    }
    private void showBrowser() {
        listVisible = false;
        if (browser != null) browser.setVisibility(View.VISIBLE);
        if (listScroll != null) listScroll.setVisibility(View.GONE);
    }
    private void pasteLink() {
        int before = links.size();
        collect(clipboardText());
        toast(links.size() > before ? "Copied Reel link saved" : "No new Reel URL on clipboard");
    }
    private void copyAll() {
        if (links.isEmpty()) { toast("No links saved yet"); return; }
        clipboard.setPrimaryClip(ClipData.newPlainText("Reel links", allLinks()));
        toast("All links copied");
    }
    private void exportTxt() {
        if (links.isEmpty()) { toast("No links saved yet"); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, "instagram_reel_links.txt");
        startActivityForResult(i, EXPORT_REQUEST);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_REQUEST && result == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new Exception("Cannot open output");
                out.write(allLinks().getBytes(StandardCharsets.UTF_8));
                toast("TXT file saved");
            } catch(Exception ex) { toast("Export failed: " + ex.getMessage()); }
        }
    }
    private void receiveSharedText(Intent i) {
        if (i != null && Intent.ACTION_SEND.equals(i.getAction()) && "text/plain".equals(i.getType())) {
            int before = links.size();
            collect(i.getStringExtra(Intent.EXTRA_TEXT));
            toast(links.size() > before ? "Shared Reel saved" : "No Reel link in shared text");
        }
    }
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        receiveSharedText(i);
    }
    @Override public void onBackPressed() {
        if (listVisible) showBrowser();
        else if (browser != null && browser.canGoBack()) browser.goBack();
        else super.onBackPressed();
    }
    @Override protected void onPause() {
        foreground = false;
        stopAuto();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (sharePending) handler.postDelayed(() -> {
            String current = clipboardText();
            if (!current.equals(clipboardBeforeAttempt)) captureClipboard();
        }, 350);
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(periodicProbe);
        handler.removeCallbacks(autoCycle);
        if (clipboard != null && clipListener != null) clipboard.removePrimaryClipChangedListener(clipListener);
        handler.removeCallbacksAndMessages(null);
        if (browser != null) browser.destroy();
        super.onDestroy();
    }
}
