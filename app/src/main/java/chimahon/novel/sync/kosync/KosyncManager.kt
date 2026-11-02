package chimahon.novel.sync.kosync

import chimahon.novel.data.BookStorage
import chimahon.novel.data.Bookmark
import chimahon.novel.data.FileNames
import kotlinx.coroutines.CancellationException
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
    /** Sync is switched on and there is a login: the library's manual actions are offered. */
    val isActive: Boolean get() = session.isActive

    /** Direction Manual: only the library selection actions sync. */
    val isManual: Boolean get() = session.isManual

    /** Whether a book pulls when it opens. */
    val autoOpen: Boolean get() = session.autoOpen

    /** Whether a book pulls when it comes back to the front after a long absence. */
    val autoForeground: Boolean get() = session.autoForeground

    /** Whether a book pushes when it is left. */
    val autoClose: Boolean get() = session.autoClose

    /** Whether an open book pushes every [periodicIntervalMins] minutes. */
    val autoPeriodic: Boolean get() = session.autoPeriodic

    val periodicIntervalMins: Int get() = session.periodicIntervalMins

    /**
     * Moves the book to a newer position from another device and returns the bookmark it became,
     * or null when the local position stands or when the position could not be written where the
     * reader resumes from. With [force] (a manual import) the server's position is taken whatever
     * its date or device. Callers wrap it in runCatching: a server problem must never keep a book
     * from opening.
     */
    suspend fun pull(bookDir: File, force: Boolean = false): Bookmark? {
        if (!session.isActive) return null
        val document = documentId(bookDir) ?: return null
        return (pullPosition(bookDir, document, force) as? PullResult.Applied)?.bookmark
    }

    /**
     * Sends the book's position unless it is (within a few characters) the one last pulled or
     * pushed. With [force] (a manual export) it is sent anyway.
     */
    suspend fun push(bookDir: File, force: Boolean = false) {
        if (!session.isActive) return
        pushPosition(bookDir, knownDocument = null, force = force)
    }

    /** The library's Auto action: a newer position from another device, else this one goes out. */
    suspend fun sync(bookDir: File): KosyncSyncOutcome = attempt(bookDir) { document ->
        when (val pulled = pullPosition(bookDir, document, force = false)) {
            is PullResult.Applied -> KosyncSyncOutcome.Pulled(pulled.device)
            PullResult.NotApplied -> KosyncSyncOutcome.Failed(NOT_APPLIED)
            PullResult.NoRecord -> if (pushPosition(bookDir, document, force = false)) {
                KosyncSyncOutcome.Pushed
            } else {
                KosyncSyncOutcome.UpToDate
            }
        }
    }

    /** The library's Import action: [pull] with force, reported. */
    suspend fun importPosition(bookDir: File): KosyncSyncOutcome = attempt(bookDir) { document ->
        when (val pulled = pullPosition(bookDir, document, force = true)) {
            is PullResult.Applied -> KosyncSyncOutcome.Pulled(pulled.device)
            PullResult.NotApplied -> KosyncSyncOutcome.Failed(NOT_APPLIED)
            PullResult.NoRecord -> KosyncSyncOutcome.NoRemotePosition
        }
    }

    /** The library's Export action: [push] with force, reported. */
    suspend fun exportPosition(bookDir: File): KosyncSyncOutcome = attempt(bookDir) { document ->
        if (pushPosition(bookDir, document, force = true)) {
            KosyncSyncOutcome.Pushed
        } else {
            KosyncSyncOutcome.NoLocalPosition
        }
    }

    private suspend fun attempt(
        bookDir: File,
        block: suspend (document: String) -> KosyncSyncOutcome,
    ): KosyncSyncOutcome {
        if (!session.isActive) return KosyncSyncOutcome.Failed("KOReader sync is off or signed out.")
        val document = documentId(bookDir) ?: return KosyncSyncOutcome.NoSourceEpub
        return try {
            block(document)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KosyncSyncOutcome.Failed(e.kosyncUserMessage())
        }
    }

    private suspend fun pullPosition(bookDir: File, document: String, force: Boolean): PullResult {
        val remote = if (force) {
            session.fetch(document)
        } else {
            val local = positionStore.load(bookDir)
            // A local position that carries no date is never replaced.
            val localSeconds = local?.let { it.lastModified?.div(1_000) ?: Long.MAX_VALUE }
            session.fetchNewer(document, localSeconds)
        } ?: return PullResult.NoRecord
        val percentage = remote.percentage ?: return PullResult.NoRecord

        // A forced position may be older than the local one; dated now, it becomes the one the
        // reader resumes from.
        val dated = if (force) null else remote.timestamp
        val bookmark = remoteBookmark(bookDir, remote.progress, percentage, dated) ?: return PullResult.NotApplied
        if (!positionStore.save(bookDir, bookmark)) return PullResult.NotApplied
        saveState(bookDir, KosyncBookState(bookmark.characterCount, remote.timestamp))
        return PullResult.Applied(bookmark, remote.device)
    }

    /** True when a position went out. [knownDocument] saves hashing the EPUB again. */
    private suspend fun pushPosition(bookDir: File, knownDocument: String?, force: Boolean): Boolean {
        val bookmark = positionStore.load(bookDir) ?: return false
        val lastSynced = loadState(bookDir).lastSyncedCharacterCount
        if (!force && lastSynced != null && abs(lastSynced - bookmark.characterCount) <= PUSH_TOLERANCE_CHARS) {
            return false
        }
        val document = knownDocument ?: documentId(bookDir) ?: return false

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
        return true
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
            this@KosyncManager.logcat(LogPriority.DEBUG) {
                "kosync: no source EPUB in '${bookDir.name}'; re-import the book to sync it"
            }
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

    private sealed interface PullResult {
        data class Applied(val bookmark: Bookmark, val device: String?) : PullResult

        /** The server holds no position to take (or none newer, when not forced). */
        data object NoRecord : PullResult

        /** The server's position could not be converted or written where the reader resumes from. */
        data object NotApplied : PullResult
    }

    private companion object {
        /** Landing on a pulled paragraph re-saves a count a few characters off; that is not a move worth pushing. */
        const val PUSH_TOLERANCE_CHARS = 10

        const val NOT_APPLIED = "The server's position could not be applied to this book."
    }
}
