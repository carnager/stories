package io.github.carnager.stories

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.widget.RemoteViews
import io.github.carnager.stories.ui.span

/**
 * The book being heard, on the home screen: its cover, how much is left,
 * and the two buttons that matter -- back a little, and play or pause.
 */
class Widget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val playback = (context.applicationContext as App).playback
        when (intent.action) {
            TOGGLE -> playback.toggle()
            REWIND -> playback.rewind()
        }
    }

    companion object {
        private const val TOGGLE = "io.github.carnager.stories.widget.TOGGLE"
        private const val REWIND = "io.github.carnager.stories.widget.REWIND"
        private var coverShown: String? = null
        private var coverBitmap: Bitmap? = null

        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, Widget::class.java))
            if (ids.isEmpty()) return
            val app = context.applicationContext as App
            val state = app.playback.state.value
            val book = app.library.book(state.bookId)
            val views = RemoteViews(context.packageName, R.layout.widget)

            if (book == null) {
                views.setTextViewText(R.id.title, "Nothing to play yet")
                views.setTextViewText(R.id.detail, "Open Stories to choose a book")
                views.setImageViewResource(R.id.cover, R.mipmap.ic_launcher)
            } else {
                val position = app.playback.position()
                val chapter = book.chapters.getOrNull(book.chapterAt(position))?.title?.takeIf { it.isNotBlank() && it != book.title }
                val left = ((book.durationMs - position) / state.speed).toLong()
                views.setTextViewText(R.id.title, book.title)
                views.setTextViewText(R.id.detail, listOfNotNull(chapter, "${span(left)} left").joinToString(" · "))
                val cover = cover(book.cover)
                if (cover != null) views.setImageViewBitmap(R.id.cover, cover) else views.setImageViewResource(R.id.cover, R.mipmap.ic_launcher)
            }
            views.setImageViewResource(R.id.toggle, if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
            views.setOnClickPendingIntent(R.id.toggle, broadcast(context, TOGGLE))
            views.setOnClickPendingIntent(R.id.rewind, broadcast(context, REWIND))
            val open = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_PLAYER, book != null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setOnClickPendingIntent(R.id.widget, PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            manager.updateAppWidget(ids, views)
        }

        private fun broadcast(context: Context, action: String) = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, Widget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** The cover, small and with rounded corners: a widget's picture travels whole to the launcher. */
        private fun cover(path: String?): Bitmap? {
            if (path == null) return null
            if (path == coverShown) return coverBitmap
            val source = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return null
            val side = 192
            val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawRoundRect(RectF(0f, 0f, side.toFloat(), side.toFloat()), 24f, 24f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(Bitmap.createScaledBitmap(source, side, side, true), 0f, 0f, paint)
            coverShown = path
            coverBitmap = out
            return out
        }
    }
}
