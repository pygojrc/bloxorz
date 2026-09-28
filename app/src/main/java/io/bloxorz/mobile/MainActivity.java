package io.bloxorz.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class MainActivity extends Activity {
    private WebView webView;
    private LocalProxyServer proxy;
    private final Map<Integer, Boolean> downSent = new HashMap<>();
    private final Map<Integer, Boolean> upPending = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        configureWebView();
        addTouchControls(root);

        proxy = new LocalProxyServer();
        try {
            proxy.start(5000, false);
            webView.loadUrl(LocalProxyServer.LOCAL_ORIGIN + "/");
        } catch (IOException e) {
            webView.loadData(
                    "<h2>Proxy failed to start</h2><pre>" + escapeHtml(e.toString()) + "</pre>",
                    "text/html", "utf-8");
        }
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String url = request.getUrl().toString();
                if (url.startsWith(LocalProxyServer.LOCAL_ORIGIN)) return false;
                if (url.startsWith(LocalProxyServer.TARGET_ORIGIN + "/")) {
                    view.loadUrl(LocalProxyServer.LOCAL_ORIGIN
                            + url.substring(LocalProxyServer.TARGET_ORIGIN.length()));
                    return true;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.requestFocus();
            }
        });
    }

    private void addTouchControls(FrameLayout root) {
        int size = dp(58);
        int gap = dp(8);
        int margin = dp(14);

        FrameLayout dpad = new FrameLayout(this);
        FrameLayout.LayoutParams dpadLp =
                new FrameLayout.LayoutParams(size * 3 + gap * 2, size * 3 + gap * 2);
        dpadLp.gravity = Gravity.BOTTOM | Gravity.START;
        dpadLp.leftMargin = margin;
        dpadLp.bottomMargin = margin;
        root.addView(dpad, dpadLp);

        addKeyButton(dpad, "▲", KeyEvent.KEYCODE_DPAD_UP, size + gap, 0, size, size);
        addKeyButton(dpad, "◀", KeyEvent.KEYCODE_DPAD_LEFT, 0, size + gap, size, size);
        addKeyButton(dpad, "▼", KeyEvent.KEYCODE_DPAD_DOWN, size + gap, size + gap, size, size);
        addKeyButton(dpad, "▶", KeyEvent.KEYCODE_DPAD_RIGHT, (size + gap) * 2, size + gap, size, size);

        TextView space = makeButton("SPACE");
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(118), size);
        sp.gravity = Gravity.BOTTOM | Gravity.END;
        sp.rightMargin = margin;
        sp.bottomMargin = margin;
        root.addView(space, sp);
        bindKey(space, KeyEvent.KEYCODE_SPACE);
    }

    private void addKeyButton(
            FrameLayout parent, String label, int keyCode,
            int left, int top, int width, int height) {
        TextView b = makeButton(label);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
        lp.leftMargin = left;
        lp.topMargin = top;
        parent.addView(b, lp);
        bindKey(b, keyCode);
    }

    private TextView makeButton(String label) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextColor(Color.WHITE);
        v.setTextSize(18);
        v.setGravity(Gravity.CENTER);
        v.setClickable(true);
        v.setFocusable(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setStroke(dp(1), 0xCCFFFFFF);
        bg.setCornerRadius(dp(12));
        v.setBackground(bg);
        return v;
    }

    private void bindKey(View button, int keyCode) {
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setAlpha(0.60f);
                    downSent.put(keyCode, false);
                    upPending.put(keyCode, false);
                    focusLargestIframeThenSendDown(keyCode);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1.0f);
                    if (Boolean.TRUE.equals(downSent.get(keyCode))) {
                        sendKey(KeyEvent.ACTION_UP, keyCode);
                        downSent.put(keyCode, false);
                    } else {
                        upPending.put(keyCode, true);
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private void focusLargestIframeThenSendDown(int keyCode) {
        webView.evaluateJavascript(
                "(function(){var a=[].slice.call(document.querySelectorAll('iframe'));"
                        + "var best=null,area=0;a.forEach(function(f){var r=f.getBoundingClientRect();"
                        + "var x=Math.max(0,r.width)*Math.max(0,r.height);"
                        + "if(x>area){area=x;best=f;}});"
                        + "if(best){try{best.focus();}catch(e){}}return !!best;})();",
                value -> {
                    webView.requestFocus();
                    sendKey(KeyEvent.ACTION_DOWN, keyCode);
                    downSent.put(keyCode, true);
                    if (Boolean.TRUE.equals(upPending.get(keyCode))) {
                        sendKey(KeyEvent.ACTION_UP, keyCode);
                        downSent.put(keyCode, false);
                        upPending.put(keyCode, false);
                    }
                });
    }

    private void sendKey(int action, int keyCode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(
                now, now, action, keyCode, 0, 0,
                KeyEvent.KEYCODE_UNKNOWN, 0,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                android.view.InputDevice.SOURCE_KEYBOARD);
        webView.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (proxy != null) proxy.stop();
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
