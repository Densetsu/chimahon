package chimahon.novel.sync.kosync

import chimahon.novel.data.BookStorage
import chimahon.novel.data.epub.EpubBook
import chimahon.novel.data.epub.EpubParser
import logcat.LogPriority
import org.w3c.dom.Element
import tachiyomi.core.common.util.system.logcat
import java.io.File

/** How the sync reads a book: the parsed EPUB and a chapter's XHTML body. Tests hand both in. */
interface KosyncBookSource {
    fun load(bookDir: File): EpubBook?
    fun chapterBody(book: EpubBook, chapterIndex: Int): Element?
}

/** The extracted book the reader opens. */
object EpubBookSource : KosyncBookSource {
    override fun load(bookDir: File): EpubBook? = try {
        BookStorage.loadEpub(bookDir)
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "kosync: could not load EPUB ${bookDir.name}" }
        null
    }

    override fun chapterBody(book: EpubBook, chapterIndex: Int): Element? = try {
        EpubParser().parseChapter(book, chapterIndex)?.let(KosyncChapterDom::parseBody)
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "kosync: could not read chapter $chapterIndex" }
        null
    }
}
