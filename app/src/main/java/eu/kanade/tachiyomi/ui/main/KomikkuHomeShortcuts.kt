package eu.kanade.tachiyomi.ui.main

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import eu.kanade.tachiyomi.R

/** Real launcher shortcuts and a one-tap Surprise Me home-screen widget. */
object KomikkuHomeShortcuts {
    const val ACTION_SURPRISE = "eu.kanade.komikku.action.SURPRISE_ME"
    const val ACTION_CONTINUE = "eu.kanade.komikku.action.CONTINUE_READING"
    const val ACTION_SEARCH_TAG = "eu.kanade.komikku.action.SEARCH_TAG"
    const val ACTION_DOWNLOADS = "eu.kanade.komikku.action.OPEN_DOWNLOADS"
    const val ACTION_COMPANION = "eu.kanade.komikku.action.OPEN_LIBRARY_COMPANION"

    const val ID_SURPRISE = "surprise_me"
    const val ID_CONTINUE = "continue_reading"
    const val ID_SEARCH_TAG = "search_tag"
    const val ID_DOWNLOADS = "downloads"

    fun configureDynamicShortcuts(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        manager.dynamicShortcuts = shortcuts(context)
    }

    fun pinShortcut(context: Context, id: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val shortcut = shortcuts(context).firstOrNull { it.id == id } ?: return false
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        return manager.requestPinShortcut(shortcut, null)
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private fun shortcuts(context: Context): List<ShortcutInfo> = listOf(
        shortcut(context, ID_SURPRISE, "Surprise Me", "Pick an unread book using your saved filters", ACTION_SURPRISE),
        shortcut(context, ID_CONTINUE, "Continue reading", "Open your most recently read chapter", ACTION_CONTINUE),
        shortcut(context, ID_SEARCH_TAG, "Search a tag", "Open the Library Companion tag filter", ACTION_SEARCH_TAG),
        shortcut(context, ID_DOWNLOADS, "Downloads", "Open the download queue", ACTION_DOWNLOADS),
    )

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private fun shortcut(context: Context, id: String, label: String, description: String, action: String): ShortcutInfo =
        ShortcutInfo.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(description)
            .setIntent(Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            .build()
}

class SurpriseMeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_surprise_me)
            val launch = Intent(context, MainActivity::class.java)
                .setAction(KomikkuHomeShortcuts.ACTION_SURPRISE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                context,
                widgetId,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_surprise_button, pending)
            manager.updateAppWidget(widgetId, views)
        }
    }
}
