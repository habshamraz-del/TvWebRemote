package com.example.tvweb

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shows a website in a WebView and injects assets/tvify.js into every page.
 *
 * Remote controls:
 *   Arrows ......... move between clickable things (TV menu or the page itself),
 *                    or move the mouse pointer when it's on
 *   OK ............. open / click the highlighted thing (or click under the pointer)
 *   Hold OK / MENU . show or hide the simplified TV menu
 *   BACK ........... close the TV menu -> previous page -> home screen
 *
 * Each site gets three switches in the TV menu: "Block ads", "Block pop-ups" and "Mouse pointer".
 */
class BrowserActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"

        /** Show the simplified TV menu automatically every time a page loads (not in mouse mode). */
        const val AUTO_OPEN_TV_MENU = true

        /**
         * null = the WebView's normal (mobile) browser identity, which usually gives
         * simpler page layouts. Set a desktop Chrome user agent here if a site needs it.
         */
        val USER_AGENT: String? = null

        private const val LONG_PRESS_MS = 500L

        // Mouse pointer speed, in dp per frame (about 60 frames a second).
        private const val MOUSE_START_SPEED = 2f
        private const val MOUSE_ACCELERATION = 0.45f
        private const val MOUSE_MAX_SPEED = 16f
    }

    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var cursor: ImageView
    private lateinit var tvScript: String

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var centerLongPressed = false

    /** The website currently shown, used for the per-site switches. */
    @Volatile
    private var pageHost: String? = null
    private val blockedCount = AtomicInteger(0)
    private var popupNoticeShown = false

    // Mouse pointer state
    private var mouseOn = false
    @Volatile
    private var menuOpen = false
    private var cursorX = -1f
    private var cursorY = -1f
    private var mouseSpeed = 0f
    private var mouseTicking = false
    private val heldArrows = mutableSetOf<Int>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startUrl = intent.getStringExtra(EXTRA_URL)
        if (startUrl == null) {
            finish()
            return
        }

        tvScript = assets.open("tvify.js").bufferedReader().use { it.readText() }
        AdBlocker.init(this)
        pageHost = Uri.parse(startUrl).host

        val root = FrameLayout(this)
        webView = WebView(this)
        fullscreenContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        val cursorSize = (36 * resources.displayMetrics.density).toInt()
        cursor = ImageView(this).apply {
            setImageResource(R.drawable.cursor)
            visibility = View.GONE
            elevation = 100f
        }
        root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(fullscreenContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(cursor, FrameLayout.LayoutParams(cursorSize, cursorSize))
        setContentView(root)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            USER_AGENT?.let { userAgentString = it }
            // Pop-ups come to onCreateWindow below, where they're blocked or opened in this screen.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        webView.addJavascriptInterface(Bridge(), "TvRemoteApp")
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Keep normal web links inside the app; ignore app-store / intent links.
                val scheme = request.url.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return true

                // Stop pages that jump to an ad site when you click anywhere.
                val host = request.url.host ?: return false
                if (request.isForMainFrame && adsBlockedHere() && isAdFromOtherSite(host)) {
                    Toast.makeText(this@BrowserActivity, "Blocked a jump to an ad site", Toast.LENGTH_SHORT).show()
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) return null
                val host = request.url.host ?: return null
                if (!adsBlockedHere() || !isAdFromOtherSite(host)) return null
                blockedCount.incrementAndGet()
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageHost = Uri.parse(url).host
                blockedCount.set(0)
                popupNoticeShown = false
                menuOpen = false
                setMouse(SiteSettings.isOn(this@BrowserActivity, pageHost, SiteSettings.MOUSE), announce = false)
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectTvScript()
            }
        }

        // Lets videos go fullscreen.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                updateCursorVisibility()
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                if (SiteSettings.isOn(this@BrowserActivity, pageHost, SiteSettings.POPUPS)) {
                    if (!popupNoticeShown) {
                        popupNoticeShown = true
                        Toast.makeText(this@BrowserActivity, "Blocked a pop-up", Toast.LENGTH_SHORT).show()
                    }
                    return false
                }
                // Pop-ups allowed on this site: open them here instead of in a new tab.
                val catcher = WebView(this@BrowserActivity)
                catcher.webViewClient = object : WebViewClient() {
                    private var done = false
                    private fun open(url: String) {
                        if (done || url == "about:blank") return
                        done = true
                        webView.loadUrl(url)
                        catcher.post { catcher.destroy() }
                    }
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        open(r.url.toString()); return true
                    }
                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) = open(url)
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = catcher
                resultMsg.sendToTarget()
                return true
            }
        }

        mouseOn = SiteSettings.isOn(this, pageHost, SiteSettings.MOUSE)
        webView.loadUrl(startUrl)
        webView.requestFocus()
    }

    // ------------------------------------------------------------------
    // Ads
    // ------------------------------------------------------------------

    private fun adsBlockedHere(): Boolean = SiteSettings.isOn(this, pageHost, SiteSettings.ADS)

    /** Ad domain that isn't part of the site you're on (so a site is never blocked from itself). */
    private fun isAdFromOtherSite(host: String): Boolean {
        val page = pageHost ?: return AdBlocker.isBlocked(host)
        if (baseDomain(host) == baseDomain(page)) return false
        return AdBlocker.isBlocked(host)
    }

    private fun baseDomain(host: String): String = host.lowercase().split('.').takeLast(2).joinToString(".")

    // ------------------------------------------------------------------
    // Bridge: lets the TV menu read and flip this site's switches
    // ------------------------------------------------------------------

    inner class Bridge {
        @JavascriptInterface
        fun getSiteSettings(): String = JSONObject()
            .put("site", SiteSettings.siteKey(pageHost))
            .put("ads", SiteSettings.isOn(applicationContext, pageHost, SiteSettings.ADS))
            .put("popups", SiteSettings.isOn(applicationContext, pageHost, SiteSettings.POPUPS))
            .put("mouse", SiteSettings.isOn(applicationContext, pageHost, SiteSettings.MOUSE))
            .put("blocked", blockedCount.get())
            .toString()

        @JavascriptInterface
        fun setSiteSetting(setting: String, on: Boolean) {
            if (setting != SiteSettings.ADS && setting != SiteSettings.POPUPS && setting != SiteSettings.MOUSE) return
            SiteSettings.set(applicationContext, pageHost, setting, on)
            when (setting) {
                // Ad blocking only applies to things the page loads, so reload to show the difference.
                SiteSettings.ADS -> runOnUiThread { webView.reload() }
                SiteSettings.MOUSE -> runOnUiThread { setMouse(on, announce = true) }
            }
        }

        @JavascriptInterface
        fun menuChanged(open: Boolean) {
            menuOpen = open
            runOnUiThread {
                heldArrows.clear()
                updateCursorVisibility()
            }
        }
    }

    private fun injectTvScript() {
        val autoOpen = AUTO_OPEN_TV_MENU && !mouseOn
        webView.evaluateJavascript(
            "window.__tvifyConfig={autoOpen:$autoOpen,mouse:$mouseOn};\n$tvScript", null
        )
    }

    /** Calls a function on window.__tvify and hands back its string result. */
    private fun callTv(call: String, onResult: ((String) -> Unit)? = null) {
        val js = "(function(){try{return window.__tvify ? window.__tvify.$call : 'unhandled';}" +
            "catch(e){return 'unhandled';}})()"
        webView.evaluateJavascript(js) { raw -> onResult?.invoke(raw?.trim('"') ?: "unhandled") }
    }

    // ------------------------------------------------------------------
    // Mouse pointer
    // ------------------------------------------------------------------

    private fun setMouse(on: Boolean, announce: Boolean) {
        val changed = on != mouseOn
        mouseOn = on
        heldArrows.clear()
        if (on && cursorX < 0) {
            // Start in the middle of the screen (after the screen has been measured).
            webView.post {
                cursorX = webView.width / 2f
                cursorY = webView.height / 2f
                placeCursor()
            }
        }
        placeCursor()
        updateCursorVisibility()
        callTv("setMouseMode($on)")
        if (announce && changed) {
            val text = if (on) "Mouse pointer on. Arrows move it, OK clicks. Hold OK for the TV menu."
            else "Mouse pointer off"
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun mouseActive() = mouseOn && !menuOpen && customView == null

    private fun updateCursorVisibility() {
        cursor.visibility = if (mouseActive()) View.VISIBLE else View.GONE
    }

    private fun placeCursor() {
        if (cursorX < 0) return
        cursor.translationX = cursorX
        cursor.translationY = cursorY
    }

    private val mouseTick = object : Runnable {
        override fun run() {
            if (heldArrows.isEmpty() || !mouseActive()) {
                mouseTicking = false
                return
            }
            val density = resources.displayMetrics.density
            mouseSpeed = minOf(mouseSpeed + MOUSE_ACCELERATION * density, MOUSE_MAX_SPEED * density)
            var dx = 0f
            var dy = 0f
            if (KeyEvent.KEYCODE_DPAD_LEFT in heldArrows) dx -= mouseSpeed
            if (KeyEvent.KEYCODE_DPAD_RIGHT in heldArrows) dx += mouseSpeed
            if (KeyEvent.KEYCODE_DPAD_UP in heldArrows) dy -= mouseSpeed
            if (KeyEvent.KEYCODE_DPAD_DOWN in heldArrows) dy += mouseSpeed
            moveCursor(dx, dy)
            cursor.postOnAnimation(this)
        }
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val w = webView.width.toFloat()
        val h = webView.height.toFloat()
        if (w <= 0 || h <= 0) return
        val wantX = cursorX + dx
        val wantY = cursorY + dy
        cursorX = wantX.coerceIn(0f, w - 1)
        cursorY = wantY.coerceIn(0f, h - 1)
        placeCursor()

        // Pushing against an edge scrolls whatever is under the pointer (page, list or carousel).
        val overX = wantX - cursorX
        val overY = wantY - cursorY
        if (overX != 0f || overY != 0f) {
            callTv("scrollAt(${cursorX / w},${cursorY / h},${overX * 2 / w},${overY * 2 / h})")
        }
        sendHover()
    }

    /** Tells the page the pointer is hovering here, so hover menus open like on a computer. */
    private fun sendHover() {
        val now = SystemClock.uptimeMillis()
        val hover = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, 0)
        hover.source = InputDevice.SOURCE_MOUSE
        webView.dispatchGenericMotionEvent(hover)
        hover.recycle()
    }

    /** A real tap at the pointer, so it works on anything a finger could press. */
    private fun tapAtCursor() {
        val x = cursorX
        val y = cursorY
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(down)
        down.recycle()
        webView.postDelayed({
            val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            webView.dispatchTouchEvent(up)
            up.recycle()
        }, 60)
    }

    // ------------------------------------------------------------------
    // Remote buttons
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While a video is fullscreen, let the player have the keys (BACK exits fullscreen).
        if (customView != null) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) exitFullscreen()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!mouseActive()) return super.dispatchKeyEvent(event) // tvify.js handles them
                if (isDown && event.repeatCount == 0) {
                    heldArrows.add(event.keyCode)
                    if (!mouseTicking) {
                        mouseTicking = true
                        mouseSpeed = MOUSE_START_SPEED * resources.displayMetrics.density
                        cursor.postOnAnimation(mouseTick)
                    }
                }
                if (isUp) heldArrows.remove(event.keyCode)
                return true
            }

            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_RED -> {
                if (isUp) callTv("toggleMenu()")
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (isDown && event.repeatCount > 0 && !centerLongPressed &&
                    event.eventTime - event.downTime >= LONG_PRESS_MS
                ) {
                    centerLongPressed = true
                    callTv("toggleMenu()")
                }
                if (isUp) {
                    if (centerLongPressed) {
                        centerLongPressed = false
                    } else if (mouseActive()) {
                        tapAtCursor()
                    } else {
                        callTv("enter()") { result ->
                            when (result) {
                                "input" -> showKeyboard()
                                "native", "unhandled" -> sendNativeEnter()
                            }
                        }
                    }
                }
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                if (isUp) handleBack()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** A real key press counts as a user action, so video play buttons and popups work. */
    private fun sendNativeEnter() {
        val now = SystemClock.uptimeMillis()
        webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
    }

    private fun showKeyboard() {
        webView.requestFocus()
        webView.postDelayed({
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun handleBack() {
        callTv("back()") { result ->
            if (result != "handled") {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        customView = null
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.requestFocus()
        updateCursorVisibility()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
            heldArrows.clear()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
