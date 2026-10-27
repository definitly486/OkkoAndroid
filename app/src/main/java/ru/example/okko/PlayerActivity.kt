package ru.example.okko

import android.app.Activity
import android.media.MediaDrm
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject

/** Embedded Media3 player. DRM license access must be authorized by the content provider. */
class PlayerActivity : Activity() {
    companion object {
        const val EXTRA_STREAM_URL = "ru.example.okko.EXTRA_STREAM_URL"
        const val EXTRA_LICENSE_URL = "ru.example.okko.EXTRA_LICENSE_URL"
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        status.text = widevineDiagnostics()
        // These extras are intended for an authorized integration that already
        // has provider-issued playback and license URLs. The WebView does not
        // scrape or extract protected media URLs from the Okko website.
        val stream = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        val license = intent.getStringExtra(EXTRA_LICENSE_URL).orEmpty()
        if (stream.isNotBlank() && license.isNotBlank()) {
            streamInput.setText(stream)
            licenseInput.setText(license)
            streamInput.post { playWithWidevine() }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val header = TextView(this).apply {
            text = "‹   Встроенный плеер"
            textSize = 19f
            setTextColor(fg)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { finish() }
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(54)))

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        playerView = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(0xFF000000.toInt())
        }
        body.addView(playerView, LinearLayout.LayoutParams(-1, dp(230)))
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
        body.addView(button("Воспроизвести с Widevine", true) { playWithWidevine() }, matchWrap())
        body.addView(space(8))
        body.addView(button("Воспроизвести видео без DRM", false) { playWithoutDrm() }, matchWrap())
        body.addView(space(8))
        body.addView(button("Проверить Widevine на устройстве", false) {
            status.text = widevineDiagnostics()
        }, matchWrap())
        body.addView(space(12))
        body.addView(label("Примечание: кнопка «Плеер» на экране Okko открывает этот экран, но не передаёт автоматически фильм из каталога. Для такой интеграции требуется официальный интерфейс воспроизведения Okko.", 12f, muted))
        body.addView(space(12))
        body.addView(label("Диагностика", 16f, fg, true))
        status = label("Проверка…", 13f, muted)
        body.addView(status, matchWrap())
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
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
        releasePlayer()
        status.text = if (drmConfiguration == null) {
            "Подготовка видео без DRM…"
        } else {
            "Подключение к потоку и запрос лицензии Widevine…"
        }
        val itemBuilder = MediaItem.Builder().setUri(Uri.parse(stream))
        if (drmConfiguration != null) itemBuilder.setDrmConfiguration(drmConfiguration)
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> status.text = "Буферизация: ожидание видео или ответа DRM-лицензии…"
                    Player.STATE_READY -> status.text = "Плеер готов."
                    Player.STATE_ENDED -> status.text = "Воспроизведение завершено."
                    Player.STATE_IDLE -> Unit
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                status.text = explainPlaybackError(error)
            }
        })
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        exo.setMediaItem(itemBuilder.build())
        exo.prepare()
        exo.playWhenReady = true
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

    private fun isHttps(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        releasePlayer()
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
}
