package chimahon.novel.sync.kosync

import chimahon.novel.data.BookStorage
import chimahon.novel.data.Bookmark
import chimahon.novel.data.FileNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reading-position sync against a KOReader kosync server, independent of the ttu/Drive sync.
 *
 * Pull applies a newer remote position: paragraph-exact when the remote xpointer resolves against
 * the chapter, character-exact when the percentage lands inside that paragraph, by percentage
 * otherwise. Push sends a crengine xpointer plus the character percentage. A document is
 * identified by a partial MD5 of the stored source EPUB, which is how KOReader identifies it too,
 * so the same file on an e-reader lines up without changing any setting there.
 */
class KosyncManager(
    private val session: KosyncSession,
    private val positionStore: KosyncPositionStore = SidecarPositionStore,
    private val books: KosyncBookSource = EpubBookSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Whether a book pulls when it opens or comes back to the front. */
    val canPull: Boolean get() = session.canPull

    /** Whether a book pushes when it is left. */
    val canPush: Boolean get() = session.canPush

    /**
     * Moves the book to a newer position from another device and returns the bookmark it became,
     * or null when the local position stands or when the position could not be written where the
     * reader resumes from. Callers wrap it in runCatching: a server problem must never keep a book
     * from opening.
     */
    suspend fun pull(bookDir: File): Bookmark? {
        if (!session.canPull) return null
        val document = documentId(bookDir) ?: return null

        val local = positionStore.load(bookDir)
        // A local position that carries no date is never replaced.
        val localSeconds = local?.let { it.lastModified?.div(1_000) ?: Long.MAX_VALUE }
        val remote = session.fetchNewer(document, localSeconds) ?: return null
        val percentage = remote.percentage ?: return null

        val bookmark = remoteBookmark(bookDir, remote.progress, percentage, remote.timestamp) ?: return null
        if (!positionStore.save(bookDir, bookmark)) return null
        saveState(bookDir, KosyncBookState(bookmark.characterCount, remote.timestamp))
        return bookmark
    }

    /** Sends the book's position unless it is (within a few characters) the one last pulled or pushed. */
    suspend fun push(bookDir: File) {
        if (!session.canPush) return
        val bookmark = positionStore.load(bookDir) ?: return
        val lastSynced = loadState(bookDir).lastSyncedCharacterCount
        if (lastSynced != null && abs(lastSynced - bookmark.characterCount) <= PUSH_TOLERANCE_CHARS) return
        val document = documentId(bookDir) ?: return

        val bookInfo = withContext(ioDispatcher) { KosyncBookIndex.loadOrBuild(bookDir, books) }
        val totalCharacters = bookInfo?.characterCount ?: 0
        val percentage = if (totalCharacters > 0) {
            (bookmark.characterCount.toDouble() / totalCharacters).coerceIn(0.0, 1.0)
        } else {
            bookmark.progress.coerceIn(0.0, 1.0)
        }

        // Always emit a pointer that resolves. KOReader applies a reflowable pull with GotoXPointer
        // and no percentage fallback, so a pointer it cannot resolve drops the device to page 1.
        val xpointer = withContext(ioDispatcher) {
            val book = books.load(bookDir)
            val rawSpine = book?.rawSpineIndex(bookmark.chapterIndex) ?: bookmark.chapterIndex
            val body = book?.let { books.chapterBody(it, bookmark.chapterIndex) }
            body?.let { KosyncXPointer.forProgress(rawSpine, it, bookmark.progress) }
                ?: KosyncXPointer.chapterStart(rawSpine)
        }

        val timestamp = session.push(document, xpointer, percentage)
        saveState(bookDir, KosyncBookState(bookmark.characterCount, timestamp))
    }

    /**
     * Turns a remote position into a local bookmark. [progress] is a crengine xpointer for
     * reflowable documents; a record without one (or with a bare page number, which KOReader
     * sends for image formats) carries no EPUB position and falls through to the percentage.
     */
    private suspend fun remoteBookmark(
        bookDir: File,
        progress: String?,
        percentage: Double,
        timestampSeconds: Long?,
    ): Bookmark? {
        val bookInfo = withContext(ioDispatcher) { KosyncBookIndex.loadOrBuild(bookDir, books) } ?: return null
        val lastModified = timestampSeconds?.times(1_000) ?: System.currentTimeMillis()
        val targetCharacter = (percentage.coerceIn(0.0, 1.0) * bookInfo.characterCount).roundToInt()

        val rawSpine = progress?.let(KosyncXPointer::spineIndex)
        val book = rawSpine?.let { withContext(ioDispatcher) { books.load(bookDir) } }
        val target = rawSpine?.let { book?.spineTarget(it) }
        if (book != null && target != null && progress != null) {
            val info = bookInfo.chapterInfo[target.chapterIndex.toString()]
            val chapterChars = info?.chapterCount ?: 0
            val resolved = if (target.exact) {
                withContext(ioDispatcher) {
                    books.chapterBody(book, target.chapterIndex)?.let { KosyncXPointer.resolve(progress, it) }
                }
            } else {
                null
            }
            var chapterProgress = resolved?.fraction
                ?: info?.takeIf {
                    chapterChars > 0 && targetCharacter in it.currentTotal until it.currentTotal + chapterChars
                }
                    ?.let { (targetCharacter - it.currentTotal).toDouble() / chapterChars }
                ?: 0.0
            var characterCount =
                info?.let { it.currentTotal + (chapterChars * chapterProgress).toInt() } ?: targetCharacter
            if (resolved != null && info != null && chapterChars > 0) {
                // The exact character wins when the percentage lands inside the resolved block: that
                // is what another Chimahon pushed; KOReader's page-based percentage rarely lands there.
                val blockStart = info.currentTotal + (chapterChars * resolved.blockStart).toInt()
                val blockEnd = info.currentTotal + (chapterChars * resolved.blockEnd).toInt()
                if (targetCharacter in blockStart..blockEnd) {
                    characterCount = targetCharacter
                    chapterProgress = (targetCharacter - info.currentTotal).toDouble() / chapterChars
                }
            }
            return Bookmark(
                chapterIndex = target.chapterIndex,
                progress = chapterProgress.coerceIn(0.0, 1.0),
                characterCount = characterCount.coerceIn(0, bookInfo.characterCount),
                lastModified = lastModified,
            )
        }

        val fallback = bookInfo.resolveCharacterPosition(targetCharacter)
        return Bookmark(
            chapterIndex = fallback?.first ?: 0,
            progress = fallback?.second ?: 0.0,
            characterCount = targetCharacter.coerceIn(0, bookInfo.characterCount),
            lastModified = lastModified,
        )
    }

    /**
     * The packed EPUB kept beside the extracted book: `book.epub` in the extraction cache of a
     * public-folder book, `<folder>.epub` for a private import. Absent for source novels and for
     * imports from before the file was kept.
     */
    private suspend fun documentId(bookDir: File): String? = withContext(ioDispatcher) {
        val epub = sourceEpub(bookDir)
        if (epub == null) {
            logcat(LogPriority.INFO) { "kosync: no source EPUB in '${bookDir.name}'; re-import the book to sync it" }
        }
        epub?.let { runCatching { KosyncDocumentId.partialMd5(it) }.getOrNull() }
    }

    private fun sourceEpub(bookDir: File): File? {
        listOf("book.epub", "${bookDir.name}.epub")
            .map { File(bookDir, it) }
            .firstOrNull { it.isFile }
            ?.let { return it }
        return bookDir.listFiles { file -> file.isFile && file.name.endsWith(".epub", ignoreCase = true) }
            ?.maxByOrNull { it.length() }
    }

    private suspend fun loadState(bookDir: File): KosyncBookState = withContext(ioDispatcher) {
        BookStorage.load<KosyncBookState>(bookDir, FileNames.kosync) ?: KosyncBookState()
    }

    private suspend fun saveState(bookDir: File, state: KosyncBookState) = withContext(ioDispatcher) {
        runCatching { BookStorage.save(state, bookDir, FileNames.kosync) }
        Unit
    }

    private companion object {
        /** Landing on a pulled paragraph re-saves a count a few characters off; that is not a move worth pushing. */
        const val PUSH_TOLERANCE_CHARS = 10
    }
}
