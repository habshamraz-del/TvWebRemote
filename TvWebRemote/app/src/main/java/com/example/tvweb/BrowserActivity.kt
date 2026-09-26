package com.example.tvweb

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Shows a website in a WebView and injects assets/tvify.js into every page.
 *
 * Remote controls:
 *   Arrows ......... move between clickable things (TV menu or the page itself)
 *   OK ............. open / click the highlighted thing
 *   Hold OK / MENU . show or hide the simplified TV menu
 *   BACK ........... close the TV menu -> previous page -> home screen
 */
class BrowserActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"

        /** Show the simplified TV menu automatically every time a page loads. */
        const val AUTO_OPEN_TV_MENU = true

        /**
         * null = the WebView's normal (mobile) browser identity, which usually gives
         * simpler page layouts. Set a desktop Chrome user agent here if a site needs it.
         */
        val USER_AGENT: String? = null

        private const val LONG_PRESS_MS = 500L
    }

    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var tvScript: String

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var centerLongPressed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startUrl = intent.getStringExtra(EXTRA_URL)
        if (startUrl == null) {
            finish()
            return
        }

        tvScript = assets.open("tvify.js").bufferedReader().use { it.readText() }

        val root = FrameLayout(this)
        webView = WebView(this)
        fullscreenContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        root.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(fullscreenContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            USER_AGENT?.let { userAgentString = it }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Keep normal web links inside the app; ignore app-store / intent links.
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
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
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }

        webView.loadUrl(startUrl)
        webView.requestFocus()
    }

    private fun injectTvScript() {
        webView.evaluateJavascript("window.__tvifyConfig={autoOpen:$AUTO_OPEN_TV_MENU};\n$tvScript", null)
    }

    /** Calls a function on window.__tvify and hands back its string result. */
    private fun callTv(call: String, onResult: ((String) -> Unit)? = null) {
        val js = "(function(){try{return window.__tvify ? window.__tvify.$call : 'unhandled';}" +
            "catch(e){return 'unhandled';}})()"
        webView.evaluateJavascript(js) { raw -> onResult?.invoke(raw?.trim('"') ?: "unhandled") }
    }

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
        // Arrow keys go straight to the page, where tvify.js handles them.
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
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
