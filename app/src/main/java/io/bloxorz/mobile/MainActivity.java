package io.bloxorz.mobile;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final String HOME_URL = "https://bloxorz.io/";
    private static final long LONG_PRESS_MS = 450L;

    private WebView webView;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int touchSlop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        prefs = getSharedPreferences("controls", MODE_PRIVATE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

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

        webView.loadUrl(HOME_URL);
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
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String host = request.getUrl().getHost();
                return host == null || !host.equalsIgnoreCase("bloxorz.io");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.requestFocus();
                view.postDelayed(MainActivity.this::focusRuffle, 700);
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

        addKeyButton(root, dpad, "▲", KeyEvent.KEYCODE_DPAD_UP,
                size + gap, 0, size, size, "dpad");
        addKeyButton(root, dpad, "◀", KeyEvent.KEYCODE_DPAD_LEFT,
                0, size + gap, size, size, "dpad");
        addKeyButton(root, dpad, "▼", KeyEvent.KEYCODE_DPAD_DOWN,
                size + gap, size + gap, size, size, "dpad");
        addKeyButton(root, dpad, "▶", KeyEvent.KEYCODE_DPAD_RIGHT,
                (size + gap) * 2, size + gap, size, size, "dpad");

        TextView space = makeButton("SPACE");
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(118), size);
        sp.gravity = Gravity.BOTTOM | Gravity.END;
        sp.rightMargin = margin;
        sp.bottomMargin = margin;
        root.addView(space, sp);
        bindKey(space, KeyEvent.KEYCODE_SPACE, space, root, "space");

        root.post(() -> {
            restorePosition(root, dpad, "dpad");
            restorePosition(root, space, "space");
        });
    }

    private void addKeyButton(
            FrameLayout root,
            FrameLayout dpad,
            String label,
            int keyCode,
            int left,
            int top,
            int width,
            int height,
            String prefKey) {
        TextView b = makeButton(label);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
        lp.leftMargin = left;
        lp.topMargin = top;
        dpad.addView(b, lp);
        bindKey(b, keyCode, dpad, root, prefKey);
    }

    private TextView makeButton(String label) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextColor(0xDDFFFFFF);
        v.setTextSize(18);
        v.setGravity(Gravity.CENTER);
        v.setClickable(true);
        v.setFocusable(false);
        v.setAlpha(0.68f);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33000000);
        bg.setStroke(dp(1), 0x88FFFFFF);
        bg.setCornerRadius(dp(12));
        v.setBackground(bg);
        return v;
    }

    private void bindKey(
            View button,
            int keyCode,
            View dragTarget,
            ViewGroup dragBounds,
            String prefKey) {

        button.setOnTouchListener(new View.OnTouchListener() {
            float downRawX;
            float downRawY;
            float targetStartX;
            float targetStartY;
            boolean pressed;
            boolean dragging;
            boolean movedTooFar;

            final Runnable beginDrag = () -> {
                if (!pressed || movedTooFar) return;
                dragging = true;
                targetStartX = dragTarget.getX();
                targetStartY = dragTarget.getY();
                button.setAlpha(0.95f);
                button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pressed = true;
                        dragging = false;
                        movedTooFar = false;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        button.setAlpha(0.88f);
                        handler.postDelayed(beginDrag, LONG_PRESS_MS);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;

                        if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                            movedTooFar = true;
                            handler.removeCallbacks(beginDrag);
                        }

                        if (dragging) {
                            setPositionClamped(
                                    dragBounds,
                                    dragTarget,
                                    targetStartX + dx,
                                    targetStartY + dy);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(beginDrag);
                        pressed = false;
                        button.setAlpha(0.68f);

                        if (dragging) {
                            dragging = false;
                            savePosition(dragBounds, dragTarget, prefKey);
                        } else if (!movedTooFar) {
                            sendTap(keyCode);
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(beginDrag);
                        pressed = false;
                        dragging = false;
                        button.setAlpha(0.68f);
                        return true;

                    default:
                        return true;
                }
            }
        });
    }

    private void sendTap(int keyCode) {
        focusRuffle(() -> {
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(
                    now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                    KeyEvent.KEYCODE_UNKNOWN, 0,
                    KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = new KeyEvent(
                    now, now + 20, KeyEvent.ACTION_UP, keyCode, 0, 0,
                    KeyEvent.KEYCODE_UNKNOWN, 0,
                    KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            webView.dispatchKeyEvent(down);
            webView.dispatchKeyEvent(up);
        });
    }

    private void focusRuffle() {
        focusRuffle(null);
    }

    private void focusRuffle(Runnable after) {
        String js =
                "(function(){"
                        + "var p=document.querySelector('ruffle-player');"
                        + "if(p){p.setAttribute('tabindex','0');try{p.focus();}catch(e){}}"
                        + "return !!p;"
                        + "})();";

        webView.evaluateJavascript(js, value -> {
            webView.requestFocus();
            if (after != null) after.run();
        });
    }

    private void setPositionClamped(ViewGroup parent, View target, float x, float y) {
        float maxX = Math.max(0, parent.getWidth() - target.getWidth());
        float maxY = Math.max(0, parent.getHeight() - target.getHeight());
        target.setX(Math.max(0, Math.min(x, maxX)));
        target.setY(Math.max(0, Math.min(y, maxY)));
    }

    private void savePosition(ViewGroup parent, View target, String key) {
        float maxX = Math.max(1, parent.getWidth() - target.getWidth());
        float maxY = Math.max(1, parent.getHeight() - target.getHeight());
        float rx = Math.max(0f, Math.min(1f, target.getX() / maxX));
        float ry = Math.max(0f, Math.min(1f, target.getY() / maxY));
        prefs.edit()
                .putFloat(key + "_x", rx)
                .putFloat(key + "_y", ry)
                .apply();
    }

    private void restorePosition(ViewGroup parent, View target, String key) {
        if (!prefs.contains(key + "_x") || !prefs.contains(key + "_y")) return;
        float rx = prefs.getFloat(key + "_x", 0f);
        float ry = prefs.getFloat(key + "_y", 0f);
        float maxX = Math.max(0, parent.getWidth() - target.getWidth());
        float maxY = Math.max(0, parent.getHeight() - target.getHeight());
        target.setX(rx * maxX);
        target.setY(ry * maxY);
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
        handler.removeCallbacksAndMessages(null);
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
}
