
package ru.example.okko

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val bg = Color.rgb(12, 13, 18)
    private val panel = Color.rgb(25, 27, 35)
    private val panelAlt = Color.rgb(39, 41, 52)
    private val fg = Color.rgb(247, 247, 250)
    private val muted = Color.rgb(166, 169, 181)
    private val accent = Color.rgb(255, 72, 110)

    private lateinit var content: LinearLayout
    private var currentTab = "Главная"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val logo = TextView(this).apply {
            text = "okko"
            textSize = 30f
            setTextColor(accent)
            typeface = Typeface.create(
                "sans-serif-black",
                Typeface.BOLD
            )
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        val login = TextView(this).apply {
            text = "Войти"
            textSize = 15f
            setTextColor(fg)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(panelAlt, 16)
            setOnClickListener { openOkko() }
        }

        header.addView(login)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(20))
        }

        scroll.addView(content)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.rgb(18, 19, 25))
        }

        listOf("Главная", "Каталог", "Поиск", "Моё").forEach { tab ->
            val item = TextView(this).apply {
                text = tab
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(if (currentTab == tab) accent else muted)
                setPadding(dp(2), dp(12), dp(2), dp(12))

                setOnClickListener {
                    currentTab = tab
                    render()
                }
            }

            nav.addView(
                item,
                LinearLayout.LayoutParams(0, dp(48), 1f)
            )
        }

        root.addView(nav)
        setContentView(root)

        showTab()
    }

    private fun showTab() {
        content.removeAllViews()

        when (currentTab) {
            "Главная" -> showHome()
            "Каталог" -> showCatalog()
            "Поиск" -> showSearch()
            "Моё" -> showProfile()
        }
    }

    private fun showHome() {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))

            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(83, 26, 62),
                    Color.rgb(39, 36, 67),
                    panel
                )
            ).apply {
                cornerRadius = dp(22).toFloat()
            }
        }

        hero.addView(label("МИР КИНО И СЕРИАЛОВ", 11f, accent, true))
        hero.addView(space(12))
        hero.addView(
            label("Ваш вечер\nначинается здесь", 29f, fg, true)
        )
        hero.addView(space(10))
        hero.addView(
            label(
                "Откройте Okko, чтобы выбрать фильм " +
                    "или продолжить просмотр.",
                14f,
                muted
            )
        )
        hero.addView(space(20))
        hero.addView(button("Открыть Okko", true) {
            openOkko()
        })

        content.addView(hero)
        content.addView(space(24))
        content.addView(label("Исследуйте", 22f, fg, true))
        content.addView(space(12))

        addCard(
            "Фильмы",
            "Премьеры и кино разных жанров",
            "Перейти к фильмам"
        )

        addCard(
            "Сериалы",
            "Истории на несколько вечеров",
            "Открыть Okko"
        )

        addCard(
            "Спорт",
            "Трансляции при наличии доступа",
            "Смотреть в Okko"
        )

        content.addView(space(10))
        content.addView(
            label(
                "Каталог, подписка и доступность видео " +
                    "определяются сервисом Okko.",
                12f,
                muted
            )
        )
    }

    private fun showCatalog() {
        content.addView(label("Каталог", 28f, fg, true))
        content.addView(space(8))
        content.addView(
            label(
                "Выбирайте раздел и переходите " +
                    "к официальному каталогу Okko.",
                14f,
                muted
            )
        )
        content.addView(space(18))

        addCard("Фильмы", "Полнометражное кино", "Открыть")
        addCard("Сериалы", "Сериалы и новые эпизоды", "Открыть")
        addCard("Спорт", "Доступные трансляции", "Открыть")
        addCard("Подписки", "Условия и доступ к контенту", "Открыть")
    }

    private fun showSearch() {
        content.addView(label("Поиск", 28f, fg, true))
        content.addView(space(8))
        content.addView(
            label(
                "Поиск по актуальному каталогу " +
                    "будет доступен на стороне Okko.",
                14f,
                muted
            )
        )
        content.addView(space(16))

        val query = EditText(this).apply {
            hint = "Название фильма или сериала"
            setHintTextColor(muted)
            setTextColor(fg)
            textSize = 16f
            setMaxLines(1)
            setHorizontallyScrolling(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(panelAlt, 14)

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    openOkko()
                    true
                } else {
                    false
                }
            }
        }

        content.addView(
            query,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(space(12))
        content.addView(button("Искать в Okko", true) {
            openOkko()
        })
    }

    private fun showProfile() {
        content.addView(label("Моё", 28f, fg, true))
        content.addView(space(8))
        content.addView(
            label(
                "Войдите в аккаунт Okko, чтобы получить " +
                    "доступ к своей библиотеке и подписке.",
                14f,
                muted
            )
        )
        content.addView(space(18))

        addCard(
            "Моя библиотека",
            "Покупки и сохранённый контент",
            "Войти в Okko"
        )

        addCard(
            "Подписка",
            "Управление на официальной стороне",
            "Открыть профиль"
        )
    }

    private fun addCard(
        title: String,
        subtitle: String,
        action: String
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(panel, 18)
        }

        card.addView(label(title, 19f, fg, true))
        card.addView(space(6))
        card.addView(label(subtitle, 13f, muted))
        card.addView(space(14))

        card.addView(button(action, false) {
            openOkko()
        })

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        params.bottomMargin = dp(12)
        content.addView(card, params)
    }

    private fun button(
        title: String,
        primary: Boolean,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = title
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)

            background = rounded(
                if (primary) accent else panelAlt,
                14
            )

            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)

            if (bold) {
                typeface = Typeface.create(
                    "sans-serif",
                    Typeface.BOLD
                )
            }

            setLineSpacing(dp(2).toFloat(), 1f)
        }
    }

    private fun space(height: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                1,
                dp(height)
            )
        }
    }

    private fun rounded(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun openOkko() {
        startActivity(
            Intent(this, OkkoWebActivity::class.java)
                .putExtra(
                    OkkoWebActivity.EXTRA_URL,
                    "https://okko.tv/"
                )
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
