package chimahon.novel.sync.kosync

import chimahon.novel.data.BookStorage
import chimahon.novel.data.Bookmark
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelHistoryRepository
import tachiyomi.domain.novel.repository.NovelRepository
import java.io.File

/** Where a book's reading position lives, keyed by the folder the reader opens. */
interface KosyncPositionStore {
    suspend fun load(bookDir: File): Bookmark?
    suspend fun save(bookDir: File, bookmark: Bookmark)
}

/** The `bookmark.json` sidecar: what unregistered books use. */
object SidecarPositionStore : KosyncPositionStore {
    override suspend fun load(bookDir: File): Bookmark? = BookStorage.loadBookmark(bookDir)
    override suspend fun save(bookDir: File, bookmark: Bookmark) = BookStorage.saveBookmark(bookmark, bookDir)
}

/**
 * The novel's DB rows, which is where the reader resumes registered books from: the latest
 * history row picks the chapter and the chapter row carries the fraction and character count,
 * in chapter-number order like the reader's own resume. Books without a row use the sidecar.
 */
class NovelDbPositionStore(
    private val novels: NovelRepository,
    private val chapters: NovelChapterRepository,
    private val history: NovelHistoryRepository,
) : KosyncPositionStore {

    override suspend fun load(bookDir: File): Bookmark? {
        val novel = novelFor(bookDir) ?: return SidecarPositionStore.load(bookDir)
        val sorted = chapters.getChaptersByNovelId(novel.id).sortedBy { it.chapterNumber }
        val latest = history.getLatestHistoryByNovelId(novel.id) ?: return null
        val index = sorted.indexOfFirst { it.id == latest.chapterId }.takeIf { it >= 0 } ?: return null
        val chapter = sorted[index]
        return Bookmark(
            chapterIndex = index,
            progress = chapter.progress.coerceIn(0.0, 1.0),
            characterCount = chapter.lastPageRead.toInt(),
            lastModified = latest.lastRead,
        )
    }

    override suspend fun save(bookDir: File, bookmark: Bookmark) {
        val novel = novelFor(bookDir) ?: return SidecarPositionStore.save(bookDir, bookmark)
        val sorted = chapters.getChaptersByNovelId(novel.id).sortedBy { it.chapterNumber }
        val chapter = sorted.getOrNull(bookmark.chapterIndex) ?: return
        chapters.update(
            NovelChapterUpdate(
                id = chapter.id,
                read = if (bookmark.progress >= 1.0) true else null,
                lastPageRead = bookmark.characterCount.toLong(),
                progress = bookmark.progress.coerceIn(0.0, 1.0),
            ),
        )
        // A zero visit only moves the resume pointer; it adds no reading time.
        history.upsertHistory(
            chapterId = chapter.id,
            lastRead = bookmark.lastModified ?: System.currentTimeMillis(),
            timeRead = 0L,
        )
    }

    private suspend fun novelFor(bookDir: File): Novel? {
        val folder = bookDir.name
        return runCatching { novels.getNovelByLocalFolder(folder) }.getOrNull()
            ?: runCatching { novels.getNovelByUrlAndSourceId("local://$folder", Novel.LOCAL_SOURCE_ID) }.getOrNull()
    }
}
