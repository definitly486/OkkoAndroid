package ru.example.okko

import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDrm
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject

/** Embedded Media3 player. DRM license access must be authorized by the content provider. */
class PlayerActivity : Activity() {
    companion object {
        const val EXTRA_STREAM_URL = "ru.example.okko.EXTRA_STREAM_URL"
        const val EXTRA_LICENSE_URL = "ru.example.okko.EXTRA_LICENSE_URL"
        const val EXTRA_USER_AGENT = "ru.example.okko.EXTRA_USER_AGENT"
        const val EXTRA_COOKIE = "ru.example.okko.EXTRA_COOKIE"
        const val EXTRA_REFERER = "ru.example.okko.EXTRA_REFERER"
        private const val LOG_TAG = "OkkoPlayer"
    }

    private val bg = 0xFF0C0D12.toInt()
    private val panel = 0xFF191B23.toInt()
    private val fg = 0xFFF7F7FA.toInt()
    private val muted = 0xFFA6A9B5.toInt()
    private val accent = 0xFFFF486E.toInt()

    private lateinit var streamInput: EditText
    private lateinit var licenseInput: EditText
    private lateinit var headersInput: EditText
    private lateinit var status: TextView
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var resumeOnStart = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var firstFrameRendered = false
    private var videoInfo = ""
    private var liveWindowRetries = 0

    // Экран открыт из WebView (передан манифест) — тогда для зашифрованного потока возвращаемся на страницу.
    private var launchedFromWebView = false
    // Итоговое объяснение, почему нет картинки; не даём ему затираться общими статусами плеера.
    private var blockedMessage: String? = null
    private lateinit var backToPageButton: Button
    private lateinit var volumeSwipe: VolumeSwipeController
    private lateinit var backgroundButton: Button
    private lateinit var audioOnlyButton: Button
    private lateinit var audioOnlyLabel: TextView
    private var backgroundPlaybackEnabled = false
    private var audioOnlyEnabled = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::volumeSwipe.isInitialized) return super.dispatchTouchEvent(ev)
        return volumeSwipe.dispatch(ev) { super.dispatchTouchEvent(it) }
    }

    // Элементы, нужные для переключения полноэкранного режима.
    private lateinit var rootFrame: FrameLayout
    private lateinit var mainColumn: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var playerContainer: FrameLayout
    private lateinit var fullscreenButton: TextView
    private var isFullscreen = false

    // Сессионные заголовки из WebView; хранятся только в памяти.
    private var userAgent: String? = null
    private val httpHeaders = linkedMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        backgroundPlaybackEnabled = getSharedPreferences("player_settings", MODE_PRIVATE)
            .getBoolean("background_playback", false)
        audioOnlyEnabled = getSharedPreferences("player_settings", MODE_PRIVATE)
            .getBoolean("audio_only", false)
        buildUi()
        updatePlaybackButtons()
        // Громкость свайпом по правому краю — в полноэкранном режиме плеера.
        volumeSwipe = VolumeSwipeController(this, rootFrame) { isFullscreen }
        safe("widevineDiagnostics") { status.text = widevineDiagnostics() }
        // These extras are intended for an authorized integration that already
        // has provider-issued playback and license URLs. The WebView does not
        // scrape or extract protected media URLs from the Okko website.
        val stream = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        val license = intent.getStringExtra(EXTRA_LICENSE_URL).orEmpty()
        userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        intent.getStringExtra(EXTRA_COOKIE)?.takeIf { it.isNotBlank() }?.let { httpHeaders["Cookie"] = it }
        intent.getStringExtra(EXTRA_REFERER)?.takeIf { it.isNotBlank() }?.let { httpHeaders["Referer"] = it }
        launchedFromWebView = stream.isNotBlank()
        if (stream.isNotBlank()) {
            streamInput.setText(stream)
            if (license.isNotBlank()) {
                licenseInput.setText(license)
                streamInput.post { playWithWidevine() }
            } else {
                // Манифест, найденный в WebView: пробуем воспроизвести без DRM.
                streamInput.post { playWithoutDrm() }
            }
        }
    }

    private fun buildUi() {
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(bg)
        }
        mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = TextView(this).apply {
            text = "‹   Встроенный плеер"
            textSize = 19f
            setTextColor(fg)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { finish() }
        }
        mainColumn.addView(header, LinearLayout.LayoutParams(-1, dp(54)))

        val scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        playerView = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(0xFF000000.toInt())
        }
        fullscreenButton = TextView(this).apply {
            text = "⛶"
            textSize = 18f
            setTextColor(fg)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { toggleFullscreen() }
        }
        playerContainer = FrameLayout(this).apply {
            addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(
                fullscreenButton,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, dp(10), dp(10), 0)
                }
            )
        }
        // Показываем/прячем кнопку вместе с элементами управления плеера.
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> fullscreenButton.visibility = visibility }
        )
        audioOnlyLabel = TextView(this).apply {
            text = "♪  Только звук — видео отключено"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(fg)
            visibility = View.GONE
            setBackgroundColor(0xFF101117.toInt())
        }
        playerContainer.addView(
            audioOnlyLabel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        body.addView(playerContainer, LinearLayout.LayoutParams(-1, dp(230)))
        body.addView(space(8))
        val playbackOptions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        audioOnlyButton = button("Только звук", false) { toggleAudioOnly() }
        backgroundButton = button("Проигрывать в фоне", false) { toggleBackgroundPlayback() }
        playbackOptions.addView(audioOnlyButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        playbackOptions.addView(backgroundButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        body.addView(playbackOptions, matchWrap())
        body.addView(space(16))
        body.addView(label("Источник видео", 16f, fg, true))
        streamInput = edit("", "HTTPS URL потока DASH (.mpd) или HLS (.m3u8)")
        body.addView(streamInput, matchWrap())
        body.addView(space(12))
        body.addView(label("URL лицензии Widevine", 16f, fg, true))
        licenseInput = edit("", "https://…/widevine-license")
        body.addView(licenseInput, matchWrap())
        body.addView(space(12))
        body.addView(label("Заголовки лицензии (необязательно)", 16f, fg, true))
        headersInput = edit("", "JSON, например {\"Authorization\":\"Bearer …\"}").apply {
            isSingleLine = false
            setLines(3)
            setHorizontallyScrolling(false)
        }
        body.addView(headersInput, matchWrap())
        body.addView(space(6))
        body.addView(label("Используйте только URL и параметры авторизации, предоставленные поставщиком контента для вашего аккаунта. Приложение не извлекает защищённые URL или ключи из сайта Okko.", 12f, muted))
        body.addView(space(14))
        body.addView(button("Воспроизвести с Widevine", true) { safe("playWithWidevine") { playWithWidevine() } }, matchWrap())
        body.addView(space(8))
        body.addView(button("Воспроизвести видео без DRM", false) { safe("playWithoutDrm") { playWithoutDrm() } }, matchWrap())
        body.addView(space(8))
        body.addView(button("Проверить Widevine на устройстве", false) {
            safe("widevineDiagnostics") { status.text = widevineDiagnostics() }
        }, matchWrap())
        body.addView(space(8))
        body.addView(button("Проверить видеокодеки (H.264)", false) {
            safe("codecDiagnostics") { status.text = codecDiagnostics() }
        }, matchWrap())
        body.addView(space(12))
        body.addView(label("Примечание: кнопка «Плеер» на экране Okko открывает этот экран, но не передаёт автоматически фильм из каталога. Для такой интеграции требуется официальный интерфейс воспроизведения Okko.", 12f, muted))
        body.addView(space(12))
        backToPageButton = button("Вернуться к странице (смотреть в WebView)", true) { finish() }
        backToPageButton.visibility = View.GONE
        body.addView(backToPageButton, matchWrap())
        body.addView(space(12))
        body.addView(label("Диагностика", 16f, fg, true))
        status = label("Проверка…", 13f, muted)
        body.addView(status, matchWrap())
        scroll.addView(body)
        mainColumn.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        rootFrame.addView(mainColumn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(rootFrame)
    }

    /** Переключает полноэкранное воспроизведение: альбомная ориентация + скрытые системные бары. */
    private fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        if (isFullscreen) return
        isFullscreen = true
        (playerContainer.parent as? ViewGroup)?.removeView(playerContainer)
        rootFrame.addView(
            playerContainer,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        playerContainer.bringToFront()
        mainColumn.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
        fullscreenButton.text = "⤢"
        reattachPlayer()
    }

    private fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        (playerContainer.parent as? ViewGroup)?.removeView(playerContainer)
        body.addView(playerContainer, 0, LinearLayout.LayoutParams(-1, dp(230)))
        mainColumn.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showSystemBars()
        fullscreenButton.text = "⛶"
        reattachPlayer()
    }

    /** После переноса PlayerView между родителями пересоздаём Surface, иначе кадр остаётся чёрным. */
    private fun reattachPlayer() {
        val p = player ?: return
        playerView.post {
            playerView.player = null
            playerView.player = p
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isFullscreen) {
            exitFullscreen()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun playWithWidevine() {
        val stream = streamInput.text.toString().trim()
        val license = licenseInput.text.toString().trim()
        if (!isHttps(stream)) {
            status.text = "Ошибка: укажите корректный HTTPS URL видео (DASH .mpd или HLS .m3u8)."
            return
        }
        if (!isHttps(license)) {
            status.text = "Ошибка лицензии: укажите HTTPS URL лицензии Widevine."
            return
        }
        if (!MediaDrm.isCryptoSchemeSupported(C.WIDEVINE_UUID)) {
            status.text = "Widevine DRM не поддерживается этим устройством."
            return
        }
        val headers = try {
            parseLicenseHeaders(headersInput.text.toString())
        } catch (e: Exception) {
            status.text = "Некорректные заголовки лицензии: ${e.message ?: "проверьте JSON"}"
            return
        }
        val drmBuilder = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
            .setLicenseUri(Uri.parse(license))
            .setMultiSession(true)
        if (headers.isNotEmpty()) drmBuilder.setLicenseRequestHeaders(headers)
        startPlayback(stream, drmBuilder.build())
    }

    private fun playWithoutDrm() {
        val stream = streamInput.text.toString().trim()
        if (!isHttps(stream)) {
            status.text = "Ошибка: укажите корректный HTTPS URL видео."
            return
        }
        startPlayback(stream, null)
    }

    private fun startPlayback(stream: String, drmConfiguration: MediaItem.DrmConfiguration?) {
        try {
            releasePlayer()
            status.text = if (drmConfiguration == null) {
                "Подготовка видео без DRM…"
            } else {
                "Подключение к потоку и запрос лицензии Widevine…"
            }
            liveWindowRetries = 0
            firstFrameRendered = false
            videoInfo = ""
            blockedMessage = null
            backToPageButton.visibility = View.GONE
            val itemBuilder = MediaItem.Builder().setUri(Uri.parse(stream))
            if (drmConfiguration != null) itemBuilder.setDrmConfiguration(drmConfiguration)
            // Для прямых эфиров (HLS live): небольшое ускорение, чтобы догонять «живой» край.
            itemBuilder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.04f).build()
            )
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .apply {
                    userAgent?.takeIf { it.isNotBlank() }?.let { setUserAgent(it) }
                    if (httpHeaders.isNotEmpty()) setDefaultRequestProperties(httpHeaders)
                }
            // Если аппаратный декодер не справился, пробуем запасной.
            val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            val exo = ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
                .build()
            player = exo
            PlaybackHolder.player = exo
            playerView.player = exo
            applyAudioOnlyMode(exo)
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING ->
                            status.text = blockedMessage ?: "Буферизация: ожидание видео или ответа DRM-лицензии…"
                        Player.STATE_READY -> {
                            status.text = blockedMessage ?: "Плеер готов."
                            // Если через 4 с кадры так и не появились — показываем, что за видеодорожки в потоке.
                            uiHandler.postDelayed({
                                if (player === exo && !firstFrameRendered && exo.playWhenReady) {
                                    status.text = blockedMessage
                                        ?: ("Плеер готов, но кадры не отрисованы.\n" +
                                            videoInfo.ifBlank { "Сведений о видеодорожках нет." })
                                }
                            }, 4000)
                        }
                        Player.STATE_ENDED -> status.text = "Воспроизведение завершено."
                        Player.STATE_IDLE -> Unit
                    }
                }
                override fun onTracksChanged(tracks: Tracks) {
                    val sb = StringBuilder()
                    var hasVideo = false
                    for (group in tracks.groups) {
                        if (group.type != C.TRACK_TYPE_VIDEO) continue
                        hasVideo = true
                        for (i in 0 until group.length) {
                            val f = group.getTrackFormat(i)
                            val fps = if (f.frameRate > 0f) " ${f.frameRate}fps" else ""
                            sb.append("Видео: ${f.sampleMimeType} ${f.codecs ?: ""} ${f.width}x${f.height}$fps, ")
                                .append("декодер ${if (group.isTrackSupported(i)) "поддерживается" else "НЕ поддерживается"}, ")
                                .append(if (group.isTrackSelected(i)) "выбрана" else "не выбрана")
                                .append("\n    поддержка: ${formatSupportName(group.getTrackSupport(i))}; ${drmInfo(f)}")
                                .append('\n')
                        }
                    }
                    // Аудиодорожки: проверяем, играет ли хотя бы звук и не зашифрован ли он.
                    for (group in tracks.groups) {
                        if (group.type != C.TRACK_TYPE_AUDIO) continue
                        for (i in 0 until group.length) {
                            val f = group.getTrackFormat(i)
                            sb.append("Аудио: ${f.sampleMimeType} ${f.codecs ?: ""}, ")
                                .append(if (group.isTrackSelected(i)) "выбрана" else "не выбрана")
                                .append("\n    поддержка: ${formatSupportName(group.getTrackSupport(i))}; ${drmInfo(f)}")
                                .append('\n')
                        }
                    }
                    videoInfo = if (hasVideo) sb.toString().trim() else "В потоке нет видеодорожки (только звук?).\n" + sb.toString().trim()
                    Log.d(LOG_TAG, videoInfo)
                    // Показываем причину чёрного экрана сразу, не дожидаясь таймера:
                    // видеодорожки есть, но ни одна не поддерживается декодером устройства.
                    val anySupportedVideo = tracks.groups.any { g ->
                        g.type == C.TRACK_TYPE_VIDEO && (0 until g.length).any { i -> g.isTrackSupported(i) }
                    }
                    if (hasVideo && !anySupportedVideo) {
                        val encrypted = tracks.groups.any { g ->
                            g.type == C.TRACK_TYPE_VIDEO &&
                                (0 until g.length).any { i -> g.getTrackFormat(i).drmInitData != null }
                        }
                        val message: String
                        if (encrypted && licenseInput.text.isBlank()) {
                            // Поток зашифрован, а DRM не настроен: ни видео, ни звук воспроизвести нельзя.
                            message = "Поток зашифрован (DRM), а URL лицензии не задан: встроенный плеер " +
                                "не может воспроизвести ни видео, ни звук. Смотрите видео на странице (WebView).\n\n$videoInfo"
                            backToPageButton.visibility = View.VISIBLE
                            if (launchedFromWebView) {
                                Toast.makeText(
                                    this@PlayerActivity,
                                    "Поток зашифрован (DRM): возвращаюсь к странице, смотрите видео там.",
                                    Toast.LENGTH_LONG
                                ).show()
                                finish()
                            }
                        } else {
                            val codecs = runCatching { codecDiagnostics() }
                                .getOrElse { "Список кодеков недоступен: ${it.javaClass.simpleName}: ${it.message}" }
                            Log.d(LOG_TAG, codecs)
                            message = "Нет поддерживаемой видеодорожки (звук может играть без картинки):\n$videoInfo\n\n$codecs"
                        }
                        blockedMessage = message
                        status.text = message
                    }
                }

                override fun onRenderedFirstFrame() {
                    firstFrameRendered = true
                    status.text = "Кадры отображаются."
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(LOG_TAG, "Ошибка воспроизведения", error)
                    // Позиция вышла за окно прямого эфира (окно ~30 с): возвращаемся к «живому» краю.
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
                        liveWindowRetries < 3
                    ) {
                        liveWindowRetries++
                        status.text = "Эфир ушёл вперёд, переподключение…"
                        exo.seekToDefaultPosition()
                        exo.prepare()
                        exo.playWhenReady = true
                        return
                    }
                    status.text = explainPlaybackError(error)
                }
            })
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            exo.setMediaItem(itemBuilder.build())
            exo.prepare()
            exo.playWhenReady = true
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Не удалось запустить воспроизведение", t)
            status.text = "Не удалось запустить плеер: ${t.javaClass.simpleName}: ${t.message ?: "без подробностей"}"
        }
    }

    private fun parseLicenseHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val json = JSONObject(raw)
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key)
            require(value is String) { "значение заголовка '$key' должно быть строкой" }
            require(key.isNotBlank() && '\r' !in key && '\n' !in key && '\r' !in value && '\n' !in value) {
                "недопустимый символ в заголовке '$key'"
            }
            result[key] = value
        }
        return result
    }

    private fun explainPlaybackError(error: PlaybackException): String {
        val chain = generateSequence<Throwable>(error) { it.cause }
            .take(8)
            .joinToString(" → ") { t -> "${t.javaClass.simpleName}: ${t.message ?: "без подробностей"}" }
        val lower = chain.lowercase()
        val category = when {
            "drm" in lower || "license" in lower || "widevine" in lower -> "Ошибка DRM/лицензии. Проверьте URL лицензии, авторизацию, заголовки запроса, срок действия прав и доступность сервера."
            "decoder" in lower || "codec" in lower -> "Ошибка декодирования: устройство может не поддерживать кодек или профиль видео."
            "http" in lower || "response code" in lower || "network" in lower -> "Сетевая ошибка: проверьте соединение, URL потока и ответ сервера."
            else -> "Не удалось воспроизвести поток. Проверьте формат, URL и доступность контента."
        }
        return "$category\n$chain"
    }

    private fun widevineDiagnostics(): String {
        if (android.os.Build.VERSION.SDK_INT < 18) return "Widevine: Android API ниже требуемого уровня."
        if (!MediaDrm.isCryptoSchemeSupported(C.WIDEVINE_UUID)) {
            return "Widevine: НЕ поддерживается. На устройстве нет доступного Widevine CDM."
        }
        return try {
            val drm = MediaDrm(C.WIDEVINE_UUID)
            val vendor = runCatching { drm.getPropertyString("vendor") }.getOrDefault("неизвестен")
            val version = runCatching { drm.getPropertyString("version") }.getOrDefault("неизвестна")
            val level = runCatching { drm.getPropertyString("securityLevel") }.getOrDefault("не определён")
            drm.release()
            "Widevine: поддерживается\nПоставщик: $vendor\nВерсия CDM: $version\nУровень безопасности: $level\nПоддержка DRM не гарантирует наличие L1: это зависит от сертификации устройства и конкретного потока."
        } catch (e: Exception) {
            "Widevine обнаружен, но диагностика не завершилась: ${e.javaClass.simpleName}: ${e.message ?: "нет подробностей"}"
        }
    }

    /**
     * Перечисляет все H.264-декодеры устройства (аппаратные и программные) с профилями/уровнями,
     * допустимыми размерами и частотой кадров. Помогает понять, почему Media3 считает дорожки
     * потока неподдерживаемыми, хотя WebView их воспроизводит.
     */
    private fun codecDiagnostics(): String {
        val mime = "video/avc"
        val sb = StringBuilder("H.264-декодеры устройства ($mime):")
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        var found = 0
        for (info in infos) {
            if (info.isEncoder || info.supportedTypes.none { it.equals(mime, true) }) continue
            found++
            sb.append("\n• ").append(info.name)
            if (Build.VERSION.SDK_INT >= 29) {
                sb.append(if (info.isHardwareAccelerated) " [аппаратный]" else " [не аппаратный]")
                if (info.isSoftwareOnly) sb.append(" [программный]")
                if (info.isAlias) sb.append(" [alias]")
            }
            try {
                val caps = info.getCapabilitiesForType(mime)
                val profiles = caps.profileLevels
                    .groupBy { it.profile }
                    .entries
                    .joinToString("; ") { (p, l) -> "${avcProfileName(p)} ≤ ${avcLevelName(l.maxOf { it.level })}" }
                sb.append("\n  профили: ").append(profiles.ifBlank { "не заявлены" })
                val secure = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                val secureReq = caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                sb.append("\n  secure: ").append(if (secureReq) "обязателен" else if (secure) "доступен" else "нет")
                val vc = caps.videoCapabilities
                if (vc != null) {
                    sb.append("\n  размеры: ${vc.supportedWidths}×${vc.supportedHeights}")
                    sb.append("\n  кадры/с: ${vc.supportedFrameRates}")
                    val checks = listOf(
                        Triple(426, 240, 25.0), Triple(854, 480, 25.0), Triple(1280, 720, 25.0),
                        Triple(1280, 720, 50.0), Triple(1920, 1080, 25.0), Triple(1920, 1080, 50.0)
                    )
                    sb.append("\n  проверка: ").append(checks.joinToString(", ") { (w, h, r) ->
                        "${w}x$h@${r.toInt()}=" + if (vc.areSizeAndRateSupported(w, h, r)) "да" else "нет"
                    })
                }
            } catch (t: Throwable) {
                sb.append("\n  ошибка чтения возможностей: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        if (found == 0) sb.append("\nНи одного H.264-декодера не найдено.")
        return sb.toString()
    }

    /** Расшифровка кода поддержки формата рендерером Media3. */
    private fun formatSupportName(support: Int): String = when (support) {
        C.FORMAT_HANDLED -> "HANDLED (можно воспроизводить)"
        C.FORMAT_EXCEEDS_CAPABILITIES -> "EXCEEDS_CAPABILITIES (превышает возможности декодера)"
        C.FORMAT_UNSUPPORTED_DRM -> "UNSUPPORTED_DRM (поток зашифрован, а DRM не настроен)"
        C.FORMAT_UNSUPPORTED_SUBTYPE -> "UNSUPPORTED_SUBTYPE (нет подходящего декодера/профиля)"
        C.FORMAT_UNSUPPORTED_TYPE -> "UNSUPPORTED_TYPE (тип/кодек не поддерживается)"
        else -> "код $support"
    }

    /** Есть ли в формате данные шифрования (ContentProtection в MPD / EXT-X-KEY) и какие схемы. */
    private fun drmInfo(format: Format): String {
        val drm = format.drmInitData ?: return "шифрование: нет"
        val schemes = (0 until drm.schemeDataCount).joinToString(", ") { idx ->
            when (val uuid = drm.get(idx).uuid) {
                C.WIDEVINE_UUID -> "Widevine"
                C.PLAYREADY_UUID -> "PlayReady"
                C.CLEARKEY_UUID -> "ClearKey"
                else -> uuid.toString()
            }
        }
        return "шифрование: ДА (${drm.schemeType ?: "?"}; $schemes)"
    }

    private fun avcProfileName(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.AVCProfileExtended -> "Extended"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High10"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 -> "High422"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> "High444"
        else -> "profile 0x" + profile.toString(16)
    }

    private fun avcLevelName(level: Int): String = when (level) {
        MediaCodecInfo.CodecProfileLevel.AVCLevel1 -> "1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel1b -> "1b"
        MediaCodecInfo.CodecProfileLevel.AVCLevel11 -> "1.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel12 -> "1.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel13 -> "1.3"
        MediaCodecInfo.CodecProfileLevel.AVCLevel2 -> "2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel21 -> "2.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel22 -> "2.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel3 -> "3"
        MediaCodecInfo.CodecProfileLevel.AVCLevel31 -> "3.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel32 -> "3.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel4 -> "4"
        MediaCodecInfo.CodecProfileLevel.AVCLevel41 -> "4.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel42 -> "4.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel5 -> "5"
        MediaCodecInfo.CodecProfileLevel.AVCLevel51 -> "5.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel52 -> "5.2"
        else -> "0x" + level.toString(16)
    }

    private fun isHttps(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun releasePlayer() {
        uiHandler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.let { current ->
            if (PlaybackHolder.player === current) PlaybackHolder.player = null
            current.release()
        }
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toggleAudioOnly() {
        audioOnlyEnabled = !audioOnlyEnabled
        getSharedPreferences("player_settings", MODE_PRIVATE).edit()
            .putBoolean("audio_only", audioOnlyEnabled).apply()
        player?.let { applyAudioOnlyMode(it) }
        updatePlaybackButtons()
    }

    private fun applyAudioOnlyMode(exo: ExoPlayer) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnlyEnabled)
            .build()
        playerView.videoSurfaceView?.visibility = if (audioOnlyEnabled) View.GONE else View.VISIBLE
        if (::audioOnlyLabel.isInitialized) {
            audioOnlyLabel.visibility = if (audioOnlyEnabled) View.VISIBLE else View.GONE
            audioOnlyLabel.bringToFront()
            if (::fullscreenButton.isInitialized) fullscreenButton.bringToFront()
        }
    }

    private fun toggleBackgroundPlayback() {
        backgroundPlaybackEnabled = !backgroundPlaybackEnabled
        getSharedPreferences("player_settings", MODE_PRIVATE).edit()
            .putBoolean("background_playback", backgroundPlaybackEnabled).apply()
        if (backgroundPlaybackEnabled) {
            try {
                val serviceIntent = android.content.Intent(this, PlaybackForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                Toast.makeText(this, "Фоновое воспроизведение включено", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                backgroundPlaybackEnabled = false
                getSharedPreferences("player_settings", MODE_PRIVATE).edit()
                    .putBoolean("background_playback", false).apply()
                Toast.makeText(this, "Не удалось включить фон: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        } else {
            stopService(android.content.Intent(this, PlaybackForegroundService::class.java))
            Toast.makeText(this, "Фоновое воспроизведение выключено", Toast.LENGTH_SHORT).show()
        }
        updatePlaybackButtons()
    }

    private fun updatePlaybackButtons() {
        if (::backgroundButton.isInitialized) {
            backgroundButton.text = if (backgroundPlaybackEnabled) "Фон: включён" else "Проигрывать в фоне"
            backgroundButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (backgroundPlaybackEnabled) accent else panel
            )
        }
        if (::audioOnlyButton.isInitialized) {
            audioOnlyButton.text = if (audioOnlyEnabled) "Только звук: вкл." else "Только звук"
            audioOnlyButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (audioOnlyEnabled) accent else panel
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (resumeOnStart) {
            resumeOnStart = false
            // Плеер освобождается в onStop(), поэтому создаём его заново: так Surface и
            // аппаратный декодер не остаются «чёрными» после возврата в приложение,
            // а прямой эфир стартует с живого края.
            if (streamInput.text.isNotBlank()) {
                safe("resumePlayback") {
                    if (licenseInput.text.isNotBlank()) playWithWidevine() else playWithoutDrm()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (backgroundPlaybackEnabled && player?.playWhenReady == true) {
            playerView.player = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        resumeOnStart = player?.playWhenReady == true
        releasePlayer()
    }

    override fun onDestroy() {
        if (::volumeSwipe.isInitialized) volumeSwipe.release()
        if (backgroundPlaybackEnabled && player?.playWhenReady == true) {
            playerView.player = null
        } else {
            releasePlayer()
        }
        super.onDestroy()
    }

    private fun edit(value: String, hintText: String) = EditText(this).apply {
        setText(value)
        hint = hintText
        setHintTextColor(muted)
        setTextColor(fg)
        textSize = 14f
        isSingleLine = true
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(panel)
    }

    private fun button(text: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(fg)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) accent else panel)
        setOnClickListener { click() }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /**
     * Выполняет [block] и, если он бросает исключение, показывает причину на экране
     * (в статусе и всплывающим сообщением) и пишет полный стек в logcat под тегом
     * "OkkoPlayer". Раньше такие ошибки либо падали молча, либо были видны только
     * через adb logcat — теперь они всегда отображаются в интерфейсе.
     */
    private fun safe(where: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Ошибка в $where", t)
            val message = "Сбой в $where: ${t.javaClass.simpleName}: ${t.message ?: "без подробностей"}"
            if (::status.isInitialized) status.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Ловит необработанные исключения (в том числе из колбэков ExoPlayer/MediaDrm,
     * которые могут выполняться не на UI-потоке) и показывает их перед тем, как
     * приложение закроется, чтобы "ничего не произошло" не выглядело как тишина.
     */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(LOG_TAG, "Необработанное исключение в потоке ${thread.name}", throwable)
            try {
                Toast.makeText(
                    applicationContext,
                    "Критическая ошибка: ${throwable.javaClass.simpleName}: ${throwable.message ?: "без подробностей"}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Throwable) {
                // Если даже Toast не удаётся показать, просто передаём обработку дальше.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
