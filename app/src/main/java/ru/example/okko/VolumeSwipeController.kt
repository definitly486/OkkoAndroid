package ru.example.okko

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Громкость медиа вертикальным свайпом по правому краю экрана (вверх — громче, вниз — тише).
 *
 * Обычные касания и горизонтальные жесты в этой зоне доходят до содержимого как раньше; только когда
 * жест распознан как вертикальный, содержимое получает ACTION_CANCEL (не прокручивается), а события
 * забирает контроллер. Индикатор добавляется в [root] и при каждом изменении поднимается поверх всех
 * дочерних view, в том числе поверх полноэкранного видео.
 *
 * Использование: в Activity переопределить dispatchTouchEvent и вернуть
 * `controller.dispatch(ev) { super.dispatchTouchEvent(it) }`.
 *
 * @param enabled когда вернёт false, касания идут как обычно (например, только в полноэкранном режиме).
 */
class VolumeSwipeController(
    private val activity: Activity,
    private val root: FrameLayout,
    private val enabled: () -> Boolean = { true }
) {
    companion object {
        // Ширина правой зоны — доля ширины экрана, но не уже 56dp.
        private const val ZONE_FRACTION = 0.16f
        // Какая часть высоты экрана соответствует полному диапазону громкости.
        private const val SWIPE_RANGE = 0.5f
    }

    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val indicator = TextView(activity).apply {
        textSize = 18f
        setTextColor(Color.WHITE)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = GradientDrawable().apply {
            setColor(0xCC000000.toInt())
            cornerRadius = dp(22).toFloat()
        }
        visibility = View.GONE
    }
    private val hideIndicator = Runnable { indicator.visibility = View.GONE }

    private var tracking = false   // касание началось в зоне громкости
    private var active = false     // жест распознан как регулировка громкости
    private var downX = 0f
    private var baseY = 0f
    private var startLevel = 0

    init {
        // Кнопки громкости на корпусе всегда меняют громкость медиа.
        activity.volumeControlStream = AudioManager.STREAM_MUSIC
        root.addView(
            indicator,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(90) }
        )
    }

    fun dispatch(ev: MotionEvent, superDispatch: (MotionEvent) -> Boolean): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = false
                val zone = (root.width * ZONE_FRACTION).coerceAtLeast(dp(56).toFloat())
                tracking = enabled() && ev.x >= root.width - zone
                downX = ev.x
                baseY = ev.y
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                if (!active) {
                    val slop = ViewConfiguration.get(activity).scaledTouchSlop
                    val dx = ev.x - downX
                    val dy = ev.y - baseY
                    if (abs(dy) > slop && abs(dy) > abs(dx) * 1.5f) {
                        active = true
                        baseY = ev.y
                        startLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        // Содержимому отправляем отмену жеста, чтобы оно не прокручивалось/не перематывалось.
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        superDispatch(cancel)
                        cancel.recycle()
                    }
                }
                if (active) {
                    applySwipe(ev.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasActive = active
                tracking = false
                active = false
                if (wasActive) {
                    handler.removeCallbacks(hideIndicator)
                    handler.postDelayed(hideIndicator, 800)
                    return true
                }
            }
        }
        return superDispatch(ev)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun applySwipe(y: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val range = (root.height * SWIPE_RANGE).coerceAtLeast(1f)
        val level = (startLevel + (baseY - y) / range * max).roundToInt().coerceIn(0, max)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        } catch (_: SecurityException) {
            // Например, режим «Не беспокоить» запрещает менять громкость — просто показываем текущую.
        }
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = (current * 100f / max).roundToInt()
        indicator.text = (if (current == 0) "🔇 " else "🔊 ") + percent + "%"
        indicator.visibility = View.VISIBLE
        // Полноэкранное видео добавляется в root позже индикатора и закрывает его — поднимаем наверх.
        if (root.getChildAt(root.childCount - 1) !== indicator) indicator.bringToFront()
        handler.removeCallbacks(hideIndicator)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
