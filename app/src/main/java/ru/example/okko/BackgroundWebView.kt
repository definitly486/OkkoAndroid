package ru.example.okko

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * WebView, который в режиме «Только звук» продолжает считать себя видимым,
 * когда Activity свёрнута/остановлена. Без этого Chromium получает onHide(),
 * переводит страницу в hidden и приостанавливает воспроизведение.
 */
class BackgroundWebView(context: Context) : WebView(context) {

    @Volatile
    var keepVisible = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(if (keepVisible) View.VISIBLE else visibility)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, if (keepVisible) View.VISIBLE else visibility)
    }
}
