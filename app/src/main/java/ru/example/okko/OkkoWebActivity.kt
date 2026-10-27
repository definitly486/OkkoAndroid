
package ru.example.okko

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class OkkoWebActivity : Activity() {

    companion object {
        const val EXTRA_URL = "ru.example.okko.EXTRA_URL"

        private const val BG = 0xFF0C0D12.toInt()
        private const val ACCENT = 0xFFFF486E.toInt()
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var header: TextView
    private lateinit var root: FrameLayout
    private lateinit var chromeClient: WebChromeClient

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = BG
        window.navigationBarColor = BG

        root = FrameLayout(this)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        header = TextView(this).apply {
            text = "‹   Okko"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            setBackgroundColor(BG)

            setOnClickListener {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        }

        page.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        webView = WebView(this)

        page.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            progressTintList =
                android.content.res.ColorStateList.valueOf(ACCENT)
        }

        root.addView(
            page,
            FrameLayout.LayoutParams(-1, -1)
        )

        root.addView(
            progress,
            FrameLayout.LayoutParams(
                -1,
                dp(3),
                Gravity.TOP
            )
        )

        setContentView(root)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            // Required so target="_blank" is captured by onCreateWindow above.
            setSupportMultipleWindows(true)

            useWideViewPort = true
            loadWithOverviewMode = true

            mixedContentMode =
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                progress.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = handleNavigation(request.url.toString())

            // Older Android WebView versions use the String overload.
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleNavigation(url)
        }

        chromeClient = object : WebChromeClient() {

            // Route target="_blank" / window.open links into the existing WebView.
            // A hidden child WebView is used only to capture the requested URL;
            // no Android browser or external activity is started.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val message = resultMsg ?: return false
                val transport = message.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(this@OkkoWebActivity)
                popup.settings.javaScriptEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        handleNavigation(request.url.toString())
                        popup.post { popup.destroy() }
                        return true
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        handleNavigation(url)
                        popup.post { popup.destroy() }
                        return true
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        if (!url.isNullOrBlank() && url != "about:blank") {
                            handleNavigation(url)
                            popup.post { popup.stopLoading(); popup.destroy() }
                        }
                    }
                }
                transport.webView = popup
                message.sendToTarget()
                return true
            }

            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int
            ) {
                progress.progress = newProgress
                progress.visibility =
                    if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowCustomView(
                view: View?,
                callback: CustomViewCallback?
            ) {
                if (view == null || fullscreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                }

                fullscreenView = view
                fullscreenCallback = callback

                header.visibility = View.GONE
                webView.visibility = View.GONE
                progress.visibility = View.GONE

                root.addView(
                    view,
                    FrameLayout.LayoutParams(-1, -1)
                )

                window.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }

            override fun onHideCustomView() {
                val view = fullscreenView ?: return

                root.removeView(view)
                fullscreenView = null

                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null

                header.visibility = View.VISIBLE
                webView.visibility = View.VISIBLE

                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }
        }

        webView.webChromeClient = chromeClient

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            val url = intent.getStringExtra(EXTRA_URL)
                ?: "https://okko.tv/"

            webView.loadUrl(url)
        }
    }

    private fun handleNavigation(rawUrl: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(rawUrl) }.getOrNull() ?: return true
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                if (::webView.isInitialized) webView.loadUrl(uri.toString())
                return true
            }
            // Do not delegate these schemes to Android, which could launch Chrome
            // or another app. Fail closed for every non-web scheme.
            else -> {
                Toast.makeText(
                    this,
                    "Внешние ссылки заблокированы: используется только WebView",
                    Toast.LENGTH_SHORT
                ).show()
                return true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (fullscreenView != null) {
            chromeClient.onHideCustomView()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
