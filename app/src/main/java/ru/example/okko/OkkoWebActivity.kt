
package ru.example.okko

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
    private lateinit var headerBar: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var chromeClient: WebChromeClient

    private lateinit var playerButton: TextView

    // Последний HLS/DASH-манифест, который страница сама запросила в WebView.
    @Volatile private var detectedStreamUrl: String? = null

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = BG
        window.navigationBarColor = BG

        // chrome://inspect для отладки EME/DRM — только в debug-сборках.
        WebView.setWebContentsDebuggingEnabled(
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        )

        root = FrameLayout(this)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG)
        }

        header = TextView(this).apply {
            text = "‹   Okko"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(8), 0)
            setBackgroundColor(BG)
            setOnClickListener {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }
        headerBar.addView(header, LinearLayout.LayoutParams(0, -1, 1f))

        playerButton = TextView(this).apply {
            text = "Плеер"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundColor(ACCENT)
            contentDescription = "Открыть встроенный видеоплеер"
            setOnClickListener { openInNativePlayer() }
        }
        val playerButtonContainer = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(12), 0)
            addView(playerButton, LinearLayout.LayoutParams(-2, dp(38)))
        }
        headerBar.addView(playerButtonContainer, LinearLayout.LayoutParams(-2, -1))

        page.addView(headerBar, LinearLayout.LayoutParams(-1, dp(52)))

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

            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            // Required so target="_blank" is captured by onCreateWindow above.
            setSupportMultipleWindows(true)

            useWideViewPort = true
            loadWithOverviewMode = true

            mixedContentMode =
                WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // Some sites reject Android WebView solely because its default UA
            // contains the "; wv" marker. Keep the device/Chrome version supplied
            // by WebView, but omit that marker and identify the compatible browser
            // engine. This is only a compatibility hint; it does not grant DRM access.
            userAgentString = userAgentString
                .replace("; wv", "")
                .let { ua ->
                    if (" Version/4.0 " !in ua && "Chrome/" in ua) {
                        ua.replace(" Chrome/", " Version/4.0 Chrome/")
                    } else {
                        ua
                    }
                }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                progress.visibility = View.VISIBLE
                detectedStreamUrl = null
                updatePlayerButton()
            }

            // Только запоминаем URL манифеста, который страница запрашивает сама.
            // Запрос не изменяется и не перехватывается: возвращаем null.
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()
                if (url != null && isManifestUrl(url) && url != detectedStreamUrl) {
                    detectedStreamUrl = url
                    runOnUiThread { updatePlayerButton() }
                }
                return null
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
            ): Boolean = handleNavigation(request.url.toString(), fromPopup = false)

            // Older Android WebView versions use the String overload.
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleNavigation(url, fromPopup = false)
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
                        handleNavigation(request.url.toString(), fromPopup = true)
                        popup.post { popup.destroy() }
                        return true
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        handleNavigation(url, fromPopup = true)
                        popup.post { popup.destroy() }
                        return true
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        if (!url.isNullOrBlank() && url != "about:blank") {
                            handleNavigation(url, fromPopup = true)
                            popup.post { popup.stopLoading(); popup.destroy() }
                        }
                    }
                }
                transport.webView = popup
                message.sendToTarget()
                return true
            }

            // Widevine/EME: страница запрашивает PROTECTED_MEDIA_ID, чтобы
            // WebView мог получить DRM-лицензию так же, как в браузере.
            // Разрешаем только этот ресурс и только для https-источников;
            // камера/микрофон и прочее отклоняются.
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val secure = request.origin?.scheme.equals("https", ignoreCase = true)
                    val allowed = request.resources
                        .filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                        .toTypedArray()
                    if (secure && allowed.isNotEmpty()) {
                        request.grant(allowed)
                    } else {
                        request.deny()
                    }
                }
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

                headerBar.visibility = View.GONE
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

                headerBar.visibility = View.VISIBLE
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

    private fun isManifestUrl(url: String): Boolean {
        val path = runCatching { android.net.Uri.parse(url).path }.getOrNull()?.lowercase() ?: return false
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
    }

    private fun updatePlayerButton() {
        val found = detectedStreamUrl != null
        playerButton.text = if (found) "▶ Плеер ●" else "Плеер"
    }

    /** Передаёт найденный манифест во встроенный Media3-плеер вместе с UA/cookies/Referer сессии. */
    private fun openInNativePlayer() {
        val stream = detectedStreamUrl
        val intent = android.content.Intent(this, PlayerActivity::class.java)
        if (stream == null) {
            Toast.makeText(
                this,
                "Поток не обнаружен: запустите видео на странице",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            intent.putExtra(PlayerActivity.EXTRA_STREAM_URL, stream)
            intent.putExtra(PlayerActivity.EXTRA_USER_AGENT, webView.settings.userAgentString)
            intent.putExtra(PlayerActivity.EXTRA_REFERER, webView.url)
            CookieManager.getInstance().getCookie(stream)?.let {
                intent.putExtra(PlayerActivity.EXTRA_COOKIE, it)
            }
            // Чтобы звук из страницы не играл параллельно с нативным плеером.
            webView.evaluateJavascript(
                "document.querySelectorAll('video,audio').forEach(function(m){m.pause()})",
                null
            )
        }
        startActivity(intent)
    }

    private fun handleNavigation(rawUrl: String, fromPopup: Boolean): Boolean {
        val uri = runCatching { android.net.Uri.parse(rawUrl) }.getOrNull() ?: return true
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                if (!fromPopup) {
                    // Let the main WebView navigate normally; do not call loadUrl()
                    // from this callback, which can re-trigger interception.
                    return false
                }
                // A target="_blank" / window.open URL is intentionally opened in
                // the existing WebView instead of starting an external browser.
                if (::webView.isInitialized) webView.loadUrl(uri.toString())
                return true
            }
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
