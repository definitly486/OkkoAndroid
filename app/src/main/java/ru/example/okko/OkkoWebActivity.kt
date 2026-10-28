
package ru.example.okko

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.content.res.Configuration
import android.util.Rational
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
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
        private const val MAX_MANIFESTS = 12
    }

    // Громкость свайпом по правому краю (работает и в полноэкранном видео).
    private lateinit var volumeSwipe: VolumeSwipeController

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var header: TextView
    private lateinit var headerBar: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var chromeClient: WebChromeClient

    private lateinit var playerButton: TextView
    private lateinit var backgroundButton: TextView
    private var webAudioBackgroundEnabled = false
    private var inPictureInPicture = false

    // Последний HLS/DASH-манифест, который страница сама запросила в WebView.
    // Запоминаем несколько последних манифестов (свежие — в конце): у страницы их бывает
    // несколько (DASH и HLS, мастер и медиа-плейлисты), а годится для встроенного плеера
    // не любой. Доступ из разных потоков — только через rememberManifest/clearManifests/manifestSnapshot.
    private val detectedManifests = ArrayList<String>()

    private fun rememberManifest(url: String): Boolean = synchronized(detectedManifests) {
        val isNew = !detectedManifests.contains(url)
        detectedManifests.remove(url)
        detectedManifests.add(url)
        while (detectedManifests.size > MAX_MANIFESTS) detectedManifests.removeAt(0)
        isNew
    }

    private fun clearManifests() = synchronized(detectedManifests) { detectedManifests.clear() }

    private fun manifestSnapshot(): List<String> = synchronized(detectedManifests) { detectedManifests.toList() }

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

        backgroundButton = TextView(this).apply {
            text = "♫ Фон"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFF343642.toInt())
            contentDescription = "Открыть видео в маленьком плавающем окне"
            setOnClickListener { enterMiniPlayer() }
        }
        backgroundButton.text = "▣ Мини-экран"
        backgroundButton.contentDescription = "Открыть видео в маленьком плавающем окне"
        backgroundButton.setOnClickListener { enterMiniPlayer() }

        val backgroundButtonContainer = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(8), 0)
            addView(backgroundButton, LinearLayout.LayoutParams(-2, dp(38)))
        }
        headerBar.addView(backgroundButtonContainer, LinearLayout.LayoutParams(-2, -1))

        val audioOnlyButton = TextView(this).apply {
            text = "♫ Только звук"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(8), dp(9), dp(8))
            setBackgroundColor(0xFF343642.toInt())
            contentDescription = "Продолжить звук в фоне"
            setOnClickListener { toggleWebAudioBackground() }
        }
        val audioOnlyButtonContainer = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(8), 0)
            addView(audioOnlyButton, LinearLayout.LayoutParams(-2, dp(38)))
        }
        headerBar.addView(audioOnlyButtonContainer, LinearLayout.LayoutParams(-2, -1))

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

        volumeSwipe = VolumeSwipeController(this, root)

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
                clearManifests()
                updatePlayerButton()
            }

            // Только запоминаем URL манифеста, который страница запрашивает сама.
            // Запрос не изменяется и не перехватывается: возвращаем null.
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()
                if (url != null && isManifestUrl(url)) {
                    if (rememberManifest(url)) runOnUiThread { updatePlayerButton() }
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

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.message().startsWith("OKKO_BG_AUDIO:")) {
                    android.util.Log.i("OkkoWebAudio", consoleMessage.message())
                }
                return true
            }

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

    /** Открывает текущий экран WebView в системном плавающем окне Android PiP. */
    private fun enterMiniPlayer() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            Toast.makeText(this, "Мини-экран поддерживается начиная с Android 8", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            setPictureInPictureParams(params)
            enterPictureInPictureMode(params)
        } catch (t: Throwable) {
            Toast.makeText(
                this,
                "Не удалось открыть мини-экран: ${t.message ?: t.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            headerBar.visibility = View.GONE
            progress.visibility = View.GONE
            // If the site has opened its fullscreen video surface, keep that surface
            // as the PiP content; otherwise the existing WebView remains visible.
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            headerBar.visibility = View.VISIBLE
            if (fullscreenView != null) {
                headerBar.visibility = View.GONE
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun isManifestUrl(url: String): Boolean {
        val path = runCatching { android.net.Uri.parse(url).path }.getOrNull()?.lowercase() ?: return false
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
    }

    private fun updatePlayerButton() {
        val found = manifestSnapshot().isNotEmpty()
        playerButton.text = if (found) "▶ Плеер ●" else "Плеер"
    }

    /** Включает фоновый режим, не меняя размеры/позицию видеоповерхности: некоторые web-плееры
     * останавливают декодирование, если video становится 1x1, уходит за экран или скрывается. */
    private fun toggleWebAudioBackground() {
        if (!webAudioBackgroundEnabled) {
            webAudioBackgroundEnabled = true
            // Keep the original WebView media element and stream. Some sites pause
            // HTML media on visibilitychange/page hide; while the user explicitly
            // enabled background audio, retry playback only if it was already playing.
            webView.evaluateJavascript(
                """
                (function() {
                  window.__okkoBackgroundAudio = true;
                  function report(v, eventName) {
                    try { console.log('OKKO_BG_AUDIO:event=' + eventName +
                      ',tag=' + v.tagName + ',paused=' + v.paused + ',ended=' + v.ended +
                      ',readyState=' + v.readyState + ',networkState=' + v.networkState +
                      ',currentTime=' + Math.round(v.currentTime) + ',error=' + (v.error ? v.error.code : 0) +
                      ',hidden=' + document.hidden); } catch (_) {}
                  }
                  console.log('OKKO_BG_AUDIO:enabled hidden=' + document.hidden +
                    ' url=' + location.href + ' mediaCount=' + document.querySelectorAll('video,audio').length);
                  function attach(v) {
                    if (v.__okkoBgAudioAttached) return;
                    v.__okkoBgAudioAttached = true;
                    v.__okkoBgWasPlaying = !v.paused && !v.ended;
                    ['play','playing','pause','waiting','stalled','suspend','ended','error','emptied','abort'].forEach(function(name) {
                      v.addEventListener(name, function() { report(v, name); }, true);
                    });
                    v.addEventListener('play', function() { v.__okkoBgWasPlaying = true; }, true);
                    v.addEventListener('playing', function() { v.__okkoBgWasPlaying = true; }, true);
                    v.addEventListener('pause', function() {
                      if (!window.__okkoBackgroundAudio || !document.hidden ||
                          !v.__okkoBgWasPlaying || v.ended) return;
                      // Defer until the site's pause handler has completed.
                      Promise.resolve().then(function() {
                        if (window.__okkoBackgroundAudio && document.hidden &&
                            v.__okkoBgWasPlaying && !v.ended) {
                          var p = v.play();
                          if (p && p.catch) p.catch(function() {});
                        }
                      });
                    }, true);
                    if (!v.hasAttribute('data-okko-original-style')) {
                      v.setAttribute('data-okko-original-style', v.getAttribute('style') || '');
                    }
                    // Hide pixels without removing the media element or changing its layout.
                    v.style.setProperty('opacity', '0.01', 'important');
                  }
                  document.querySelectorAll('video,audio').forEach(attach);
                  if (!window.__okkoBgAudioObserver) {
                    window.__okkoBgAudioObserver = new MutationObserver(function() {
                      document.querySelectorAll('video,audio').forEach(attach);
                    });
                    window.__okkoBgAudioObserver.observe(document.documentElement,
                      {childList:true, subtree:true});
                  }
                  document.addEventListener('visibilitychange', function() {
                    console.log('OKKO_BG_AUDIO:visibility hidden=' + document.hidden +
                      ' mediaCount=' + document.querySelectorAll('video,audio').length);
                    if (!window.__okkoBackgroundAudio || !document.hidden) return;
                    document.querySelectorAll('video,audio').forEach(function(v) {
                      if (v.__okkoBgWasPlaying && v.paused && !v.ended) {
                        var p = v.play(); if (p && p.catch) p.catch(function(){});
                      }
                    });
                  });
                  return true;
                })()
                """.trimIndent(),
                null
            )
            // Keep the fullscreen custom view fully opaque and attached. The Activity is
            // moved behind other tasks, so its pixels are not visible to the user anyway;
            // lowering alpha can let the compositor treat the video surface as transparent.
            try {
                val serviceIntent = android.content.Intent(this, PlaybackForegroundService::class.java)
                    .putExtra("web_audio_mode", true)
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                Toast.makeText(this, "Режим звука включён. Для полного выключения дисплея нажмите кнопку питания.", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } catch (t: Throwable) {
                webAudioBackgroundEnabled = false
                webView.evaluateJavascript(
                    "window.__okkoBackgroundAudio=false;" +
                        "if(window.__okkoBgAudioObserver){window.__okkoBgAudioObserver.disconnect();window.__okkoBgAudioObserver=null;}" +
                        "(function(){document.querySelectorAll('[data-okko-original-style]').forEach(function(v){" +
                        "var s=v.getAttribute('data-okko-original-style');" +
                        "if(s){v.setAttribute('style',s);}else{v.removeAttribute('style');}" +
                        "v.removeAttribute('data-okko-original-style');v.__okkoBgAudioAttached=false;});return true;})()",
                    null
                )
                Toast.makeText(this, "Не удалось включить фон: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        } else {
            webAudioBackgroundEnabled = false
            webView.evaluateJavascript(
                "window.__okkoBackgroundAudio=false;" +
                    "if(window.__okkoBgAudioObserver){window.__okkoBgAudioObserver.disconnect();window.__okkoBgAudioObserver=null;}" +
                    "(function(){document.querySelectorAll('[data-okko-original-style]').forEach(function(v){" +
                    "var s=v.getAttribute('data-okko-original-style');" +
                    "if(s){v.setAttribute('style',s);}else{v.removeAttribute('style');}" +
                    "v.removeAttribute('data-okko-original-style');v.__okkoBgAudioAttached=false;});return true;})()",
                null
            )
            stopService(android.content.Intent(this, PlaybackForegroundService::class.java))
            Toast.makeText(this, "Фоновый режим выключен", Toast.LENGTH_SHORT).show()
        }
    }

    /** Результат разбора манифеста. null = определить не удалось. */
    private class ManifestInfo(
        val url: String,
        /** true — поток зашифрован (ContentProtection / EXT-X-KEY), false — нет. */
        val encrypted: Boolean?,
        /** true — полный манифест (MPD или HLS master), false — медиа-плейлист одной дорожки. */
        val master: Boolean?
    )

    /**
     * Скачивает начало манифеста (с теми же UA/Referer/cookies, что у страницы) и определяет,
     * зашифрован ли он и является ли он полным манифестом. Ключи и лицензии не запрашиваются.
     */
    private fun inspectManifest(url: String, userAgent: String, referer: String?): ManifestInfo {
        var conn: java.net.HttpURLConnection? = null
        try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn = c
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("User-Agent", userAgent)
            if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
            CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
            if (c.responseCode !in 200..299) return ManifestInfo(url, null, null)
            val text = c.inputStream.use { stream ->
                val buf = ByteArray(256 * 1024)
                var n = 0
                while (n < buf.size) {
                    val r = stream.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                String(buf, 0, n, Charsets.UTF_8)
            }
            return when {
                text.contains("<MPD", ignoreCase = true) ->
                    ManifestInfo(url, text.contains("ContentProtection", ignoreCase = true), true)
                text.contains("#EXTM3U") -> {
                    val master = text.contains("#EXT-X-STREAM-INF")
                    val keyed = Regex("#EXT-X-(SESSION-)?KEY:[^\\n]*METHOD=(?!NONE)").containsMatchIn(text)
                    // У HLS-мастера ключей обычно нет: шифрование видно только в медиа-плейлисте.
                    val encrypted = if (keyed) true else if (master) null else false
                    ManifestInfo(url, encrypted, master)
                }
                else -> ManifestInfo(url, null, null)
            }
        } catch (t: Throwable) {
            return ManifestInfo(url, null, null)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Выбирает манифест для встроенного плеера: сначала незашифрованные, потом неизвестные,
     * зашифрованные — в последнюю очередь; среди равных — полные манифесты и более свежие.
     */
    private fun pickBestManifest(candidates: List<String>, userAgent: String, referer: String?): ManifestInfo? {
        if (candidates.isEmpty()) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val futures = candidates.map { u ->
                pool.submit(java.util.concurrent.Callable { inspectManifest(u, userAgent, referer) })
            }
            val infos = futures.mapIndexed { idx, f ->
                runCatching { f.get(8, java.util.concurrent.TimeUnit.SECONDS) }
                    .getOrElse { ManifestInfo(candidates[idx], null, null) }
            }
            // candidates: свежие в конце, сортировка стабильная — разворачиваем, чтобы свежие были первыми.
            return infos.reversed().sortedWith(
                compareBy<ManifestInfo>(
                    { when (it.encrypted) { false -> 0; null -> 1; true -> 2 } },
                    { if (it.master == false) 1 else 0 }
                )
            ).first()
        } finally {
            pool.shutdownNow()
        }
    }

    /** Открывает встроенный Media3-плеер для найденного манифеста (с UA/cookies/Referer сессии). */
    private fun openInNativePlayer() {
        val candidates = manifestSnapshot()
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Поток не обнаружен: запустите видео на странице", Toast.LENGTH_SHORT).show()
            startActivity(android.content.Intent(this, PlayerActivity::class.java))
            return
        }
        val userAgent = webView.settings.userAgentString
        val referer = webView.url
        playerButton.isEnabled = false
        Toast.makeText(this, "Проверяю найденные потоки…", Toast.LENGTH_SHORT).show()
        Thread {
            val best = pickBestManifest(candidates, userAgent, referer)
            runOnUiThread {
                playerButton.isEnabled = true
                if (isFinishing) return@runOnUiThread
                if (best == null || best.encrypted == true) {
                    Toast.makeText(
                        this,
                        "Поток зашифрован (DRM): встроенный плеер без лицензии его не покажет. Смотрите видео на странице.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    launchNativePlayer(best.url, userAgent, referer)
                }
            }
        }.start()
    }

    private fun launchNativePlayer(stream: String, userAgent: String, referer: String?) {
        val intent = android.content.Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_STREAM_URL, stream)
        intent.putExtra(PlayerActivity.EXTRA_USER_AGENT, userAgent)
        intent.putExtra(PlayerActivity.EXTRA_REFERER, referer)
        CookieManager.getInstance().getCookie(stream)?.let {
            intent.putExtra(PlayerActivity.EXTRA_COOKIE, it)
        }
        // Чтобы звук из страницы не играл параллельно с нативным плеером.
        webView.evaluateJavascript(
            "document.querySelectorAll('video,audio').forEach(function(m){m.pause()})",
            null
        )
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::volumeSwipe.isInitialized) return super.dispatchTouchEvent(ev)
        return volumeSwipe.dispatch(ev) { super.dispatchTouchEvent(it) }
    }

    override fun onPause() {
        android.util.Log.i("OkkoWebAudio", "Activity onPause; backgroundMode=$webAudioBackgroundEnabled fullscreen=${fullscreenView != null} view=${fullscreenView?.javaClass?.name} shown=${fullscreenView?.isShown} alpha=${fullscreenView?.alpha}")
        super.onPause()
    }

    override fun onStop() {
        android.util.Log.i("OkkoWebAudio", "Activity onStop; backgroundMode=$webAudioBackgroundEnabled fullscreen=${fullscreenView != null} view=${fullscreenView?.javaClass?.name} shown=${fullscreenView?.isShown} alpha=${fullscreenView?.alpha}")
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.i("OkkoWebAudio", "Activity onResume; backgroundMode=$webAudioBackgroundEnabled fullscreen=${fullscreenView != null} view=${fullscreenView?.javaClass?.name} shown=${fullscreenView?.isShown} alpha=${fullscreenView?.alpha}")
    }

    override fun onDestroy() {
        android.util.Log.i("OkkoWebAudio", "Activity onDestroy; backgroundMode=$webAudioBackgroundEnabled")
        if (::volumeSwipe.isInitialized) volumeSwipe.release()
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
