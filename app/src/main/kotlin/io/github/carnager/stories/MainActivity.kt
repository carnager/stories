package io.github.carnager.stories

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.carnager.stories.data.ServerAddress
import io.github.carnager.stories.ui.ConnectDialog
import io.github.carnager.stories.ui.MainScreen
import io.github.carnager.stories.ui.NewerPlaceDialog
import io.github.carnager.stories.ui.Nav
import io.github.carnager.stories.ui.SetupScreen
import io.github.carnager.stories.ui.StoriesTheme
import io.github.carnager.stories.ui.hasStorage

class MainActivity : ComponentActivity() {
    // Read again on every return: the permission is granted in system settings.
    private val storage = mutableStateOf(hasStorage())
    private val nav = Nav()
    private val connectTo = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        if (intent?.getBooleanExtra(EXTRA_PLAYER, false) == true) nav.player = true
        connectTo.value = serverIn(intent)
        setContent {
            val settings by app.settings.state.collectAsState()
            val granted by remember { storage }
            val address by remember { connectTo }
            val server by app.sync.status.collectAsState()
            StoriesTheme(settings) {
                // With a server there may be no folders on the phone at all.
                if (server.url == null && (!granted || settings.roots.isEmpty())) SetupScreen(app, granted)
                else MainScreen(app, nav)
                address?.let { ConnectDialog(app, it, onClose = { connectTo.value = null }) }
                NewerPlaceDialog(app)
            }
        }
    }

    /** The address in a stories://connect link, as a desktop's code holds. It only fills in the form. */
    private fun serverIn(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "stories" || uri.host != "connect") return null
        return runCatching { uri.getQueryParameter("url") }.getOrNull()?.let(ServerAddress::normalize)
    }

    override fun onResume() {
        super.onResume()
        val had = storage.value
        storage.value = hasStorage()
        val app = application as App
        app.sync.now()
        // New files may have come while away; a rescan only reads what changed.
        if (storage.value && app.settings.value.roots.isNotEmpty() && (!had || app.library.books.value.isEmpty() || stale())) {
            app.library.rescan()
        }
    }

    private var lastScan = 0L

    private fun stale(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastScan < 10 * 60_000) return false
        lastScan = now
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_PLAYER, false)) nav.player = true
        serverIn(intent)?.let { connectTo.value = it }
    }

    override fun onStop() {
        super.onStop()
        (application as App).library.flush()
    }

    companion object {
        const val EXTRA_PLAYER = "player"
    }
}
