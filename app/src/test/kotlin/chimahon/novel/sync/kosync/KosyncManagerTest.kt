package chimahon.novel.sync.kosync

import chimahon.novel.data.Bookmark
import chimahon.novel.data.FileNames
import chimahon.novel.data.epub.EpubBook
import chimahon.novel.data.epub.EpubSpine
import chimahon.novel.data.epub.SpineItem
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.File

/**
 * The decisions the sync makes: when a remote position replaces the local one, what a push
 * carries. The book has two linear chapters of 20 counted characters each; chapter XHTML is
 * handed in through [FakeBooks] so no EPUB has to exist on disk.
 */
class KosyncManagerTest {
    @TempDir
    lateinit var bookDir: File

    private val api = FakeKosyncApi()
    private val positions = object : KosyncPositionStore {
        var bookmark: Bookmark? = null
        override suspend fun load(bookDir: File): Bookmark? = bookmark
        override suspend fun save(bookDir: File, bookmark: Bookmark): Boolean {
            this.bookmark = bookmark
            return true
        }
    }

    private val preferences = KosyncPreferences(FakePreferenceStore()).apply {
        enabled().set(true)
        saveLogin(KosyncCredentials("http://server", "reader", "key"))
    }

    private val chapterXhtml = "<html><body><p>あいうえおかきくけこ</p><p>さしすせそたちつてと</p></body></html>"

    private val twoChapters = EpubBook(
        spine = EpubSpine(items = listOf(SpineItem(idref = "c0"), SpineItem(idref = "c1"))),
    )

    private class FakeBooks(val book: EpubBook?, val chapters: Map<Int, String> = emptyMap()) : KosyncBookSource {
        override fun load(bookDir: File): EpubBook? = book
        override fun chapterBody(book: EpubBook, chapterIndex: Int): Element? =
            chapters[chapterIndex]?.let(KosyncChapterDom::parseBody)
    }

    private fun manager(books: KosyncBookSource = FakeBooks(null)) = KosyncManager(
        session = KosyncSession(preferences, api, deviceName = "Chimahon Test"),
        positionStore = positions,
        books = books,
    )

    private val readable = FakeBooks(twoChapters, mapOf(0 to chapterXhtml, 1 to chapterXhtml))

    @BeforeEach
    fun book() {
        File(bookDir, "book.epub").writeBytes(ByteArray(4096) { it.toByte() })
        val index = BookInfo(
            characterCount = 40,
            chapterInfo = mapOf(
                "0" to ChapterInfo(chapterIndex = 0, currentTotal = 0, chapterCount = 20),
                "1" to ChapterInfo(chapterIndex = 1, currentTotal = 20, chapterCount = 20),
            ),
        )
        File(bookDir, FileNames.bookinfo).writeText(Json.encodeToString(BookInfo.serializer(), index))
    }

    private fun pull(books: KosyncBookSource = FakeBooks(null)) = runBlocking { manager(books).pull(bookDir) }
    private fun push(books: KosyncBookSource = FakeBooks(null)) = runBlocking { manager(books).push(bookDir) }

    @Test
    fun `a newer position from another device replaces the local one`() {
        positions.bookmark = Bookmark(chapterIndex = 0, progress = 0.1, characterCount = 2, lastModified = 1_000_000L)
        api.remote = remoteProgress(percentage = 0.75, timestamp = 2_000L)

        val pulled = pull()

        pulled shouldBe Bookmark(chapterIndex = 1, progress = 0.5, characterCount = 30, lastModified = 2_000_000L)
        positions.bookmark shouldBe pulled
        api.pulls shouldBe listOf(KosyncDocumentId.partialMd5(File(bookDir, "book.epub")))
    }

    @Test
    fun `a pointer that resolves lands on its block`() {
        api.remote = remoteProgress(progress = "/body/DocFragment[2]/body/p[2]", percentage = 0.3, timestamp = 2_000L)

        pull(readable)

        // p[2] starts 10 characters into chapter 1; the percentage (12 characters) is outside it,
        // so the block start wins.
        positions.bookmark shouldBe
            Bookmark(chapterIndex = 1, progress = 0.5, characterCount = 30, lastModified = 2_000_000L)
    }

    @Test
    fun `the percentage refines the position inside the resolved block`() {
        api.remote = remoteProgress(progress = "/body/DocFragment[2]/body/p[2]", percentage = 0.875, timestamp = 2_000L)

        pull(readable)

        // 0.875 × 40 = 35, inside p[2] (characters 30..40 of the book): exact.
        positions.bookmark shouldBe
            Bookmark(chapterIndex = 1, progress = 0.75, characterCount = 35, lastModified = 2_000_000L)
    }

    @Test
    fun `a pointer that cannot be resolved falls back to the percentage inside its chapter`() {
        api.remote = remoteProgress(progress = "/body/DocFragment[2]/body/p[40]", percentage = 0.75, timestamp = 2_000L)

        pull(readable)

        positions.bookmark shouldBe
            Bookmark(chapterIndex = 1, progress = 0.5, characterCount = 30, lastModified = 2_000_000L)
    }

    @Test
    fun `a pointer that cannot be resolved and a percentage elsewhere land on the chapter start`() {
        api.remote = remoteProgress(progress = "/body/DocFragment[2]/body/p[40]", percentage = 0.25, timestamp = 2_000L)

        pull(readable)

        positions.bookmark shouldBe
            Bookmark(chapterIndex = 1, progress = 0.0, characterCount = 20, lastModified = 2_000_000L)
    }

    @Test
    fun `a pointer into a non-linear spine item lands on the next chapter start`() {
        val withCover = EpubBook(
            spine = EpubSpine(
                items = listOf(
                    SpineItem(idref = "c0"),
                    SpineItem(idref = "ad", linear = false),
                    SpineItem(idref = "c1"),
                ),
            ),
        )
        api.remote = remoteProgress(progress = "/body/DocFragment[2]/body/div[1]", percentage = 0.1, timestamp = 2_000L)

        pull(FakeBooks(withCover, mapOf(0 to chapterXhtml, 1 to chapterXhtml)))

        positions.bookmark shouldBe
            Bookmark(chapterIndex = 1, progress = 0.0, characterCount = 20, lastModified = 2_000_000L)
    }

    @Test
    fun `the local position stands against an older, an undated or an own remote position`() {
        val local = Bookmark(chapterIndex = 0, progress = 0.1, characterCount = 2, lastModified = 3_000_000L)
        positions.bookmark = local

        api.remote = remoteProgress(percentage = 0.75, timestamp = 2_000L)
        pull()
        api.remote = remoteProgress(percentage = 0.75, timestamp = 3_000L)
        pull()
        api.remote = remoteProgress(percentage = 0.75, timestamp = null)
        pull()
        api.remote = remoteProgress(percentage = 0.75, timestamp = 9_000L, deviceId = preferences.deviceId)
        pull()
        api.remote = remoteProgress(percentage = null, timestamp = 9_000L)
        pull()

        positions.bookmark shouldBe local
        pull().shouldBeNull()
    }

    @Test
    fun `a local position with no date is never replaced`() {
        val local = Bookmark(chapterIndex = 0, progress = 0.1, characterCount = 2, lastModified = null)
        positions.bookmark = local
        api.remote = remoteProgress(percentage = 0.75, timestamp = 9_000L)

        pull()

        positions.bookmark shouldBe local
    }

    @Test
    fun `a book never opened here takes the remote position, dated or not`() {
        api.remote = remoteProgress(percentage = 0.75, timestamp = null)

        pull()

        positions.bookmark?.characterCount shouldBe 30
    }

    @Test
    fun `a position the store cannot write is neither applied nor recorded as synced`() {
        val local = Bookmark(chapterIndex = 0, progress = 0.1, characterCount = 2, lastModified = 1_000_000L)
        positions.bookmark = local
        val unwritable = KosyncManager(
            session = KosyncSession(preferences, api, deviceName = "Chimahon Test"),
            positionStore = object : KosyncPositionStore by positions {
                override suspend fun save(bookDir: File, bookmark: Bookmark): Boolean = false
            },
            books = FakeBooks(null),
        )
        api.remote = remoteProgress(percentage = 0.75, timestamp = 2_000L)

        runBlocking { unwritable.pull(bookDir) }.shouldBeNull()
        api.pulls.size shouldBe 1
        positions.bookmark shouldBe local
        File(bookDir, FileNames.kosync).exists() shouldBe false

        // Nothing was recorded as synced, so a moved position goes out, even at the count the pull
        // would have recorded.
        positions.bookmark = Bookmark(chapterIndex = 1, progress = 0.5, characterCount = 30, lastModified = 3_000_000L)
        push()
        api.puts.single().percentage shouldBe 0.75
    }

    @Test
    fun `nothing is asked of the server when sync is off, signed out or the book has no source file`() {
        api.remote = remoteProgress(percentage = 0.75, timestamp = 2_000L)
        positions.bookmark = Bookmark(chapterIndex = 1, progress = 0.5, characterCount = 30, lastModified = 1L)

        preferences.enabled().set(false)
        pull()
        push()
        runBlocking { manager().pull(bookDir, force = true) }
        runBlocking { manager().push(bookDir, force = true) }
        runBlocking { manager().sync(bookDir) } shouldBe
            KosyncSyncOutcome.Failed("KOReader sync is off or signed out.")
        preferences.enabled().set(true)
        preferences.clearLogin()
        pull()
        push()
        preferences.saveLogin(KosyncCredentials("http://server", "reader", "key"))
        File(bookDir, "book.epub").delete()
        runBlocking { manager().sync(bookDir) } shouldBe KosyncSyncOutcome.NoSourceEpub
        pull()
        push()

        api.pulls shouldBe emptyList()
        api.puts shouldBe emptyList()
    }

    @Test
    fun `a private import's epub is found by the folder name`() {
        File(bookDir, "book.epub").delete()
        File(bookDir, "${bookDir.name}.epub").writeBytes(ByteArray(2048) { (it * 7).toByte() })
        api.remote = remoteProgress(percentage = 0.75, timestamp = 2_000L)

        pull()

        api.pulls shouldBe listOf(KosyncDocumentId.partialMd5(File(bookDir, "${bookDir.name}.epub")))
    }

    @Test
    fun `a push sends the character percentage and a pointer KOReader can resolve`() {
        positions.bookmark = Bookmark(chapterIndex = 1, progress = 0.75, characterCount = 35, lastModified = 1L)

        push(readable)

        val put = api.puts.single()
        put.percentage shouldBe 0.875
        put.progress shouldBe "/body/DocFragment[2]/body/p[2]"
        put.device shouldBe "Chimahon Test"
        put.deviceId shouldBe preferences.deviceId
    }

    @Test
    fun `an unreadable chapter pushes the chapter start, a pulled or barely moved position is not pushed`() {
        positions.bookmark = Bookmark(chapterIndex = 1, progress = 0.5, characterCount = 30, lastModified = 1L)
        push(FakeBooks(twoChapters))
        api.puts.single().progress shouldBe "/body/DocFragment[2]/body"

        // Within 10 characters of what was just sent: nothing new to say.
        positions.bookmark = Bookmark(chapterIndex = 1, progress = 0.55, characterCount = 31, lastModified = 2L)
        push()
        api.puts.size shouldBe 1

        positions.bookmark = Bookmark(chapterIndex = 1, progress = 0.75, characterCount = 35, lastModified = 3L)
        push()
        api.puts.size shouldBe 1
        positions.bookmark = Bookmark(chapterIndex = 0, progress = 0.1, characterCount = 2, lastModified = 4L)
        push()
        api.puts.size shouldBe 2

        // A pull records what the server holds, so landing there pushes nothing back.
        api.remote = remoteProgress(percentage = 0.75, timestamp = 2_000_000L)
        pull()
        push()
        api.puts.size shouldBe 2
    }

    @Test
    fun `a manual import applies an older record from another device`() {
        positions.bookmark = Bookmark(chapterIndex = 0, progress = 0.1, characterCount = 2, lastModified = 3_000_000L)
        api.remote = remoteProgress(percentage = 0.75, timestamp = 2_000L)

        pull().shouldBeNull()
        positions.bookmark?.characterCount shouldBe 2

        val before = System.currentTimeMillis()
        val pulled = runBlocking { manager().pull(bookDir, force = true) }

        pulled?.characterCount shouldBe 30
        positions.bookmark shouldBe pulled
        // Dated now, so the reader resumes from it rather than from the newer local position.
        (pulled!!.lastModified!! >= before) shouldBe true

        // Even a record this device pushed itself is taken, and the import is reported by device.
        api.remote = remoteProgress(percentage = 0.25, timestamp = 1_000L, deviceId = preferences.deviceId)
        runBlocking { manager().importPosition(bookDir) } shouldBe KosyncSyncOutcome.Pulled("Kobo")
        positions.bookmark?.characterCount shouldBe 10

        api.remote = null
        runBlocking { manager().importPosition(bookDir) } shouldBe KosyncSyncOutcome.NoRemotePosition
    }

    @Test
    fun `a manual export ignores the push tolerance`() {
        positions.bookmark = Bookmark(chapterIndex = 1, progress = 0.5, characterCount = 30, lastModified = 1L)
        push()
        api.puts.size shouldBe 1

        // Within 10 characters of what was just sent: an automatic push skips it, an export does not.
        positions.bookmark = Bookmark(chapterIndex = 1, progress = 0.55, characterCount = 31, lastModified = 2L)
        push()
        api.puts.size shouldBe 1
        runBlocking { manager().push(bookDir, force = true) }
        api.puts.size shouldBe 2
        runBlocking { manager().exportPosition(bookDir) } shouldBe KosyncSyncOutcome.Pushed
        api.puts.size shouldBe 3
    }

    @Test
    fun `the library's Auto action pulls a newer position, else pushes, else is up to date`() {
        positions.bookmark = Bookmark(chapterIndex = 0, progress = 0.1, characterCount = 2, lastModified = 1_000_000L)
        api.remote = remoteProgress(percentage = 0.75, timestamp = 2_000L)
        runBlocking { manager().sync(bookDir) } shouldBe KosyncSyncOutcome.Pulled("Kobo")
        api.puts.size shouldBe 0

        // Landed on the pulled position: nothing newer, nothing moved.
        runBlocking { manager().sync(bookDir) } shouldBe KosyncSyncOutcome.UpToDate

        positions.bookmark = Bookmark(chapterIndex = 0, progress = 0.5, characterCount = 10, lastModified = 9_000_000L)
        runBlocking { manager().sync(bookDir) } shouldBe KosyncSyncOutcome.Pushed
        api.puts.size shouldBe 1
    }
}
