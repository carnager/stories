package io.github.carnager.stories.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.carnager.stories.App
import io.github.carnager.stories.MainActivity

/**
 * Keeps playing with the app closed, and gives the lock screen, the
 * notification, headphones and cars the app's one player. Its back and
 * forward buttons step through the book rather than from file to file.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val playback = App.instance.playback
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setDisplayName("Back")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setDisplayName("Forward")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
        session = MediaSession.Builder(this, playback.player)
            .setSessionActivity(open)
            .setMediaButtonPreferences(listOf(back, forward))
            .setCallback(object : MediaSession.Callback {
                // Play pressed on headphones with the app gone: the book last heard.
                @Deprecated("Superseded upstream; still called")
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val player = playback.exo
                    val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition)
                    )
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        App.instance.library.flush()
        // The player is the app's and outlives the service; only the session goes.
        session?.release()
        session = null
        super.onDestroy()
    }
}
