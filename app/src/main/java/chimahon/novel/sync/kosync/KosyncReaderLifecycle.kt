package chimahon.novel.sync.kosync

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import chimahon.novel.ui.reader.NovelReaderActivity
import chimahon.novel.ui.reader.ReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * The novel reader's KOReader sync around leaving a book and coming back to it. The first open
 * is covered by the pull in `ReaderScreen`, before the view model seeds its resume position.
 *
 * The activity calls these from its own `onResume` / `onStop`, after the view model flushed its
 * rows. This is deliberately not a lifecycle observer: observers hear the stop before the
 * activity's own `onStop` has flushed the chapter rows the push reads.
 */
class KosyncReaderLifecycle(
    private val activity: ComponentActivity,
    private val viewModel: () -> ReaderViewModel?,
) {
    private val manager: KosyncManager? by lazy { runCatching { Injekt.get<KosyncManager>() }.getOrNull() }

    private fun bookDir(): File? =
        activity.intent.getStringExtra(NovelReaderActivity.EXTRA_BOOK_DIR)?.let(::File)

    /**
     * Coming back after a long absence: another device may have pushed a newer position while
     * this one was away. The pull writes storage, then the view model re-seeds from it.
     */
    fun pullAfterForeground() {
        val manager = manager?.takeIf { it.canPull } ?: return
        val bookDir = bookDir() ?: return
        activity.lifecycleScope.launch {
            val pulled = withContext(Dispatchers.IO) { runCatching { manager.pull(bookDir) }.getOrNull() }
            if (pulled != null) viewModel()?.onPositionImported()
        }
    }

    /** Leaving: the view model has just flushed its rows, so the push sends the final position. */
    fun pushOnStop() {
        val manager = manager?.takeIf { it.canPush } ?: return
        val bookDir = bookDir() ?: return
        closingScope.launch { runCatching { manager.push(bookDir) } }
    }

    private companion object {
        /** Outlives the activity so a closing push is not cancelled with it. */
        val closingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
