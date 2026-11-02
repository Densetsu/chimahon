package chimahon.novel.sync.kosync

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
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
 *
 * The reader hands in the two things this needs from it, so this package does not depend on the
 * reader's: [bookDir], the open book's folder, and [onImported], which re-seeds the reader after a
 * pull moved the book.
 */
class KosyncReaderLifecycle(
    private val activity: ComponentActivity,
    private val bookDir: () -> File?,
    private val onImported: () -> Unit,
) {
    private val manager: KosyncManager? by lazy { runCatching { Injekt.get<KosyncManager>() }.getOrNull() }

    /**
     * Coming back after a long absence: another device may have pushed a newer position while
     * this one was away. The pull writes storage, then the reader re-seeds from it.
     */
    fun pullAfterForeground() {
        val manager = manager?.takeIf { it.autoForeground } ?: return
        val bookDir = bookDir() ?: return
        activity.lifecycleScope.launch {
            val pulled = withContext(Dispatchers.IO) {
                runCatching { manager.pull(bookDir) }
                    .onFailure { Log.w("NovelReader", "kosync pull failed", it) }
                    .getOrNull()
            }
            if (pulled != null) onImported()
        }
    }

    /** Leaving: the view model has just flushed its rows, so the push sends the final position. */
    fun pushOnStop() {
        val manager = manager?.takeIf { it.autoClose } ?: return
        val bookDir = bookDir() ?: return
        closingScope.launch {
            runCatching { manager.push(bookDir) }.onFailure { Log.w("NovelReader", "kosync push failed", it) }
        }
    }

    private companion object {
        /** Outlives the activity so a closing push is not cancelled with it. */
        val closingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
