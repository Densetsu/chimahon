package chimahon.novel.sync.kosync

import chimahon.novel.data.BookStorage
import chimahon.novel.data.Bookmark
import chimahon.novel.data.FileNames
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapter
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.domain.novel.model.NovelHistory
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelHistoryRepository
import tachiyomi.domain.novel.repository.NovelRepository
import java.io.File

/**
 * Where a registered book's position is read and written: the rows the reader resumes from, in
 * the same order, with the same sidecar fallback.
 */
class NovelDbPositionStoreTest {
    @TempDir
    lateinit var bookDir: File

    private val novels = mockk<NovelRepository>()
    private val chapters = mockk<NovelChapterRepository>()
    private val history = mockk<NovelHistoryRepository>()
    private val store = NovelDbPositionStore(novels, chapters, history)

    private val sidecar = Bookmark(chapterIndex = 3, progress = 0.4, characterCount = 1_234, lastModified = 5_000L)

    @Test
    fun `a book without a novel row reads and writes the sidecar`() {
        coEvery { novels.getNovelByLocalFolder(bookDir.name) } returns null
        coEvery { novels.getNovelByUrlAndSourceId("local://${bookDir.name}", Novel.LOCAL_SOURCE_ID) } returns null
        writeSidecar(sidecar)

        runBlocking { store.load(bookDir) } shouldBe sidecar

        val moved = Bookmark(chapterIndex = 4, progress = 0.5, characterCount = 1_500, lastModified = 6_000L)
        runBlocking { store.save(bookDir, moved) } shouldBe true
        BookStorage.loadBookmark(bookDir) shouldBe moved
    }

    @Test
    fun `a registered novel without a usable history row resumes from the sidecar`() {
        registered(chapter(id = 11L, number = 1f), chapter(id = 12L, number = 2f))
        writeSidecar(sidecar)

        coEvery { history.getLatestHistoryByNovelId(NOVEL_ID) } returns null
        runBlocking { store.load(bookDir) } shouldBe sidecar

        // History on a chapter the novel does not have is no better.
        coEvery { history.getLatestHistoryByNovelId(NOVEL_ID) } returns
            NovelHistory(id = 1L, chapterId = 99L, lastRead = 9_000L, timeRead = 0L)
        runBlocking { store.load(bookDir) } shouldBe sidecar
    }

    @Test
    fun `a position for a chapter without a row is reported as not written`() {
        registered(chapter(id = 11L, number = 1f), chapter(id = 12L, number = 2f))
        val beyond = Bookmark(chapterIndex = 2, progress = 0.5, characterCount = 100, lastModified = 10_000L)

        runBlocking { store.save(bookDir, beyond) } shouldBe false

        coVerify(exactly = 0) { chapters.update(any()) }
        coVerify(exactly = 0) { history.upsertHistory(any(), any(), any()) }
        File(bookDir, FileNames.bookmark).exists() shouldBe false
    }

    @Test
    fun `a failed chapter update is reported`() {
        registered(chapter(id = 11L, number = 1f), chapter(id = 12L, number = 2f))
        coEvery { chapters.update(any()) } returns false
        val moved = Bookmark(chapterIndex = 1, progress = 0.5, characterCount = 600, lastModified = 10_000L)

        runBlocking { store.save(bookDir, moved) } shouldBe false

        coVerify(exactly = 0) { history.upsertHistory(any(), any(), any()) }
    }

    @Test
    fun `a registered novel reads and writes its rows in chapter-number order`() {
        // Repository order is not reading order: id 12 is the second chapter.
        registered(
            chapter(id = 13L, number = 3f),
            chapter(id = 11L, number = 1f),
            chapter(id = 12L, number = 2f, progress = 0.25, lastPageRead = 640L),
        )
        coEvery { history.getLatestHistoryByNovelId(NOVEL_ID) } returns
            NovelHistory(id = 1L, chapterId = 12L, lastRead = 9_000L, timeRead = 60_000L)
        coEvery { chapters.update(any()) } returns true
        coEvery { history.upsertHistory(any(), any(), any()) } returns true

        runBlocking { store.load(bookDir) } shouldBe
            Bookmark(chapterIndex = 1, progress = 0.25, characterCount = 640, lastModified = 9_000L)

        val moved = Bookmark(chapterIndex = 2, progress = 0.5, characterCount = 1_200, lastModified = 10_000L)
        runBlocking { store.save(bookDir, moved) } shouldBe true
        coVerify(exactly = 1) {
            chapters.update(NovelChapterUpdate(id = 13L, read = null, lastPageRead = 1_200L, progress = 0.5))
        }
        coVerify(exactly = 1) { history.upsertHistory(chapterId = 13L, lastRead = 10_000L, timeRead = 0L) }

        // Finished is the reader's threshold, not exactly 1.0.
        val finished = Bookmark(chapterIndex = 0, progress = 0.9995, characterCount = 400, lastModified = 11_000L)
        runBlocking { store.save(bookDir, finished) } shouldBe true
        coVerify(exactly = 1) {
            chapters.update(NovelChapterUpdate(id = 11L, read = true, lastPageRead = 400L, progress = 0.9995))
        }
    }

    private fun registered(vararg rows: NovelChapter) {
        coEvery { novels.getNovelByLocalFolder(bookDir.name) } returns novel()
        coEvery { chapters.getChaptersByNovelId(NOVEL_ID) } returns rows.toList()
    }

    private fun writeSidecar(bookmark: Bookmark) {
        File(bookDir, FileNames.bookmark).writeText(Json.encodeToString(Bookmark.serializer(), bookmark))
    }

    private fun novel() = Novel(
        id = NOVEL_ID,
        source = Novel.LOCAL_SOURCE_ID,
        favorite = true,
        lastUpdate = 0L,
        nextUpdate = 0L,
        fetchInterval = 0,
        dateAdded = 0L,
        coverLastModified = 0L,
        url = "local://${bookDir.name}",
        title = "Test novel",
        author = null,
        artist = null,
        description = null,
        genre = null,
        status = Novel.UNKNOWN,
        thumbnailUrl = null,
        initialized = true,
        lastModifiedAt = 0L,
        favoriteModifiedAt = null,
        totalChapters = 3,
        version = 0L,
        notes = "",
        isLocal = true,
        localFolder = bookDir.name,
    )

    private fun chapter(id: Long, number: Float, progress: Double = 0.0, lastPageRead: Long = 0L) = NovelChapter(
        id = id,
        novelId = NOVEL_ID,
        read = false,
        bookmark = false,
        lastPageRead = lastPageRead,
        dateFetch = 0L,
        sourceOrder = 0L,
        url = "chapter-$id",
        name = "Chapter $id",
        dateUpload = 0L,
        chapterNumber = number,
        scanlator = null,
        lastModifiedAt = 0L,
        version = 1L,
        progress = progress,
    )

    private companion object {
        const val NOVEL_ID = 7L
    }
}
