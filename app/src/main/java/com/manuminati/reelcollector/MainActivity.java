package com.manuminati.reelcollector;

import android.app.Activity;
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int EXPORT_REQUEST = 42;
    private static final Pattern REEL_PATTERN = Pattern.compile("https?://(?:www\\.)?instagram\\.com/reel/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private WebView browser;
    private TextView status;
    private LinearLayout savedList;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> links = new HashSet<>();
    private boolean listVisible = false;
    private boolean autoScroll = false;
    private boolean foreground = true;
    private String detection = "waiting";
    private boolean domProbeRunning = false;
    private Button autoButton;
    private static final long SCROLL_INTERVAL_MS = 3000L;
    // This is a best-effort touch swipe on the embedded WebView, not an Instagram API.
    private final Runnable scrollTask = new Runnable() {
        @Override public void run() {
            if (!autoScroll || !foreground || browser == null || listVisible) return;
            swipeUpSlowly();
            collect(browser.getUrl());
            probeVisibleReel();
            handler.postDelayed(this, SCROLL_INTERVAL_MS);
        }
    };
    private final Runnable watcher = new Runnable() {
        @Override public void run() {
            if (browser != null && foreground && !listVisible) {
                collect(browser.getUrl());
                probeVisibleReel();
            }
            handler.postDelayed(this, 1200);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        links.addAll(getPreferences(MODE_PRIVATE).getStringSet("reels", Collections.emptySet()));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(16, 12, 16, 12);
        root.addView(status);
        updateCount();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);
        addButton(row, "Reels", () -> { showBrowser(); browser.loadUrl("https://www.instagram.com/reels/"); });
        addButton(row, "Save URL", () -> {
            if (browser != null) {
                int before = links.size();
                collect(browser.getUrl());
                probeVisibleReel();
                Toast.makeText(this, links.size() > before ? "Reel saved" : "Checking visible Reel link...", Toast.LENGTH_SHORT).show();
            }
        });
        addButton(row, "Saved", this::showList);

        LinearLayout autoRow = new LinearLayout(this);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(autoRow);
        autoButton = addButton(autoRow, "Start Auto Scroll (3s)", this::toggleAutoScroll);
        addButton(autoRow, "Stop", this::stopAutoScroll);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row2);
        addButton(row2, "Copy All", this::copyAll);
        addButton(row2, "Export TXT", this::exportTxt);
        addButton(row2, "Clear", () -> new android.app.AlertDialog.Builder(this)
            .setTitle("Delete all saved links?")
            .setPositiveButton("Delete", (d, w) -> { links.clear(); persist(); renderList(); })
            .setNegativeButton("Cancel", null).show());

        browser = new WebView(this);
        browser.getSettings().setJavaScriptEnabled(true); // Instagram's website needs JavaScript
        browser.getSettings().setDomStorageEnabled(true);
        browser.getSettings().setMediaPlaybackRequiresUserGesture(false);
        browser.getSettings().setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!request.isForMainFrame()) return false;
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
                if (host.equals("instagram.com") || host.endsWith(".instagram.com")) {
                    collect(uri.toString());
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                catch (Exception ignored) { Toast.makeText(MainActivity.this, "Can't open link", Toast.LENGTH_SHORT).show(); }
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

        ScrollView scroll = new ScrollView(this);
        scroll.setVisibility(View.GONE);
        savedList = new LinearLayout(this);
        savedList.setPadding(16, 12, 16, 12);
        savedList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(savedList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        this.listScroll = scroll;
        renderList();
        browser.loadUrl("https://www.instagram.com/reels/");
        handler.postDelayed(watcher, 1200);
        handleSharedText(getIntent());
    }
    private ScrollView listScroll;
    private Button addButton(LinearLayout row, String name, Runnable click) {
        Button btn = new Button(this);
        btn.setText(name);
        btn.setTextSize(11);
        btn.setAllCaps(false);
        row.addView(btn, new LinearLayout.LayoutParams(0, -2, 1));
        btn.setOnClickListener(v -> click.run());
        return btn;
    }
    private void updateCount() {
        if (status != null) status.setText("Saved: " + links.size() + " | Auto-scroll: "
            + (autoScroll ? "ON (every 3s)" : "OFF") + " | Detect: " + detection);
    }
    private void toggleAutoScroll() {
        if (autoScroll) { stopAutoScroll(); return; }
        if (listVisible) showBrowser();
        autoScroll = true;
        autoButton.setText("Pause Auto Scroll");
        updateCount();
        handler.removeCallbacks(scrollTask);
        handler.postDelayed(scrollTask, SCROLL_INTERVAL_MS);
        Toast.makeText(this, "Auto scroll ON: 1 swipe every 3 seconds", Toast.LENGTH_SHORT).show();
    }
    private void stopAutoScroll() {
        autoScroll = false;
        handler.removeCallbacks(scrollTask);
        if (autoButton != null) autoButton.setText("Start Auto Scroll (3s)");
        updateCount();
    }
    private void swipeUpSlowly() {
        if (browser.getWidth() < 1 || browser.getHeight() < 150 || !browser.isShown()) return;
        // A gradual touch gesture (~450ms) in the WebView to advance the feed.
        final float x = browser.getWidth() * 0.5f;
        final float startY = browser.getHeight() * 0.80f;
        final float endY = browser.getHeight() * 0.22f;
        final long downAt = SystemClock.uptimeMillis();
        dispatchSwipeEvent(MotionEvent.ACTION_DOWN, x, startY, downAt, downAt);
        final int steps = 12;
        for (int i = 1; i <= steps; i++) {
            final int index = i;
            handler.postDelayed(() -> {
                if (!autoScroll || !foreground || listVisible || browser == null) return;
                float y = startY + (endY - startY) * index / steps;
                int action = index == steps ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                dispatchSwipeEvent(action, x, y, downAt, SystemClock.uptimeMillis());
                if (index == steps) handler.postDelayed(() -> {
                    if (browser != null) { collect(browser.getUrl()); probeVisibleReel(); }
                }, 500);
            }, i * 35L);
        }
    }
    private void dispatchSwipeEvent(int action, float x, float y, long downTime, long eventTime) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        try { browser.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    // Find ONLY the Reel currently visible on screen, not every link in the feed.
    // Instagram frequently changes video without changing WebView.getUrl().
    // This runs in the page's own DOM; no private Instagram API or password access.
    private void probeVisibleReel() {
        if (browser == null || !foreground || listVisible || domProbeRunning) return;
        domProbeRunning = true;
        final String js = """
            (function() {
              try {
                const rx = /\\/reel\\/[A-Za-z0-9_-]+/;
                const vw = innerWidth, vh = innerHeight;
                function clean(h) {
                  if (!h) return null;
                  try {
                    const u = new URL(h, location.href);
                    if (!/(^|\\.)instagram\\.com$/i.test(u.hostname)) return null;
                    const m = u.pathname.match(/^\\/reel\\/([A-Za-z0-9_-]+)/i);
                    return m ? 'https://www.instagram.com/reel/' + m[1] + '/' : null;
                  } catch(e) { return null; }
                }
                function visible(el) {
                  const r = el.getBoundingClientRect();
                  if (r.width < 1 || r.height < 1) return null;
                  const x = Math.max(0, Math.min(r.right,vw)-Math.max(r.left,0));
                  const y = Math.max(0, Math.min(r.bottom,vh)-Math.max(r.top,0));
                  if (x*y < 20) return null;
                  return { r, area:x*y };
                }
                // When the browser URL actually points to a reel, it is the strongest signal.
                const loc = clean(location.href);
                if (loc) return JSON.stringify({url:loc,method:'page-url'});
                // Detect the currently playing/central video and its own enclosing permalink.
                const vids = Array.from(document.querySelectorAll('video'))
                  .map(v => ({v, pos:visible(v)})).filter(o => o.pos)
                  .sort((a,b) => ((b.v.paused ? 0 : 10000000)+b.pos.area) -
                                 ((a.v.paused ? 0 : 10000000)+a.pos.area));
                for (const {v} of vids) {
                  let node = v;
                  for (let depth=0; node && depth<9; depth++,node=node.parentElement) {
                    if (node.matches && node.matches('a[href]')) {
                      const u=clean(node.href); if(u) return JSON.stringify({url:u,method:'video-anchor'});
                    }
                    if (node.querySelectorAll) {
                      const found = Array.from(node.querySelectorAll('a[href*="/reel/"]'))
                        .map(a=>clean(a.href)).filter(Boolean);
                      const unique=Array.from(new Set(found));
                      if (unique.length===1) return JSON.stringify({url:unique[0],method:'video-container'});
                    }
                  }
                }
                // Fallback: pick the most central, visible reel permalink.
                const candidates = Array.from(document.querySelectorAll('a[href*="/reel/"]'))
                  .map(a=>({u:clean(a.href),p:visible(a)}))
                  .filter(o=>o.u && o.p)
                  .map(o=>({u:o.u,score: o.p.area /
                    (1+Math.abs((o.p.r.top+o.p.r.bottom)/2-vh/2)/vh)}))
                  .sort((a,b)=>b.score-a.score);
                // Multiple different reel links visible is ambiguous: do not save a wrong one.
                if (candidates.length && (candidates.length===1 ||
                     candidates[0].score > candidates[1].score * 2))
                  return JSON.stringify({url:candidates[0].u,method:'visible-link'});
                return JSON.stringify({url:null,method:vids.length ? 'no-permalink' : 'no-video'});
              } catch(e) { return JSON.stringify({url:null,method:'js-error'}); }
            })();
            """;
        browser.evaluateJavascript(js, result -> {
            domProbeRunning = false;
            if (result == null) return;
            try {
                // evaluateJavascript wraps the returned JSON string in another JSON string.
                Object decoded = new org.json.JSONTokener(result).nextValue();
                JSONObject obj = new JSONObject(String.valueOf(decoded));
                String url = obj.optString("url", "");
                String method = obj.optString("method", "unknown");
                if (!url.isEmpty() && !"null".equals(url)) collect(url);
                detection = method;
                updateCount();
            } catch(Exception ignored) { detection = "parse-error"; updateCount(); }
        });
    }

    private void collect(String url) {
        if (url == null) return;
        Matcher m = REEL_PATTERN.matcher(url);
        if (m.find()) {
            String clean = "https://www.instagram.com/reel/" + m.group(1) + "/";
            if (links.add(clean)) { persist(); renderList(); }
        }
    }
    private void persist() {
        getPreferences(MODE_PRIVATE).edit().putStringSet("reels", new HashSet<>(links)).apply();
        updateCount();
    }
    private List<String> sortedLinks() {
        List<String> items = new ArrayList<>(links);
        Collections.sort(items);
        return items;
    }
    private String allLinks() { return android.text.TextUtils.join("\n", sortedLinks()); }
    private void renderList() {
        if (savedList == null) return;
        savedList.removeAllViews();
        for (String link : sortedLinks()) {
            TextView item = new TextView(this);
            item.setText(link);
            item.setTextSize(14);
            item.setPadding(0, 8, 0, 8);
            item.setTextIsSelectable(true);
            item.setOnClickListener(v -> {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Reel", link));
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            });
            savedList.addView(item);
        }
        updateCount();
    }
    private void showList() {
        stopAutoScroll();
        listVisible = true;
        browser.setVisibility(View.GONE);
        listScroll.setVisibility(View.VISIBLE);
    }
    private void showBrowser() {
        listVisible = false;
        browser.setVisibility(View.VISIBLE);
        listScroll.setVisibility(View.GONE);
    }
    private void copyAll() {
        if (links.isEmpty()) { Toast.makeText(this, "No links saved", Toast.LENGTH_SHORT).show(); return; }
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Reel Links", allLinks()));
        Toast.makeText(this, "All links copied", Toast.LENGTH_SHORT).show();
    }
    private void exportTxt() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "instagram_reel_links.txt");
        startActivityForResult(intent, EXPORT_REQUEST);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_REQUEST && result == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new Exception("Output unavailable");
                out.write(allLinks().getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "TXT exported", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(this, "Export error: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedText(intent);
    }
    private void handleSharedText(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            int before = links.size();
            collect(intent.getStringExtra(Intent.EXTRA_TEXT));
            Toast.makeText(this, links.size() > before ? "Shared Reel saved" : "No new Reel URL in share", Toast.LENGTH_SHORT).show();
        }
    }
    @Override public void onBackPressed() {
        if (listVisible) showBrowser();
        else if (browser.canGoBack()) browser.goBack();
        else super.onBackPressed();
    }
    @Override protected void onPause() {
        foreground = false;
        stopAutoScroll(); // never scroll in the background
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        foreground = true;
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(scrollTask);
        handler.removeCallbacks(watcher);
        if (browser != null) browser.destroy();
        super.onDestroy();
    }
}
