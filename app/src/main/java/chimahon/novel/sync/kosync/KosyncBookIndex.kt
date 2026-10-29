package chimahon.novel.sync.kosync

import chimahon.novel.data.BookStorage
import chimahon.novel.data.FileNames
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ChapterInfo(
    val chapterIndex: Int,
    val currentTotal: Int,
    val chapterCount: Int,
)

/**
 * Character totals per chapter, cached as `bookinfo.json` beside the book.
 *
 * KOReader exchanges a percentage of the whole document, while the reader tracks a chapter index
 * plus a fraction, so both directions of the sync convert through these prefix sums.
 */
@Serializable
data class BookInfo(
    val characterCount: Int,
    val chapterInfo: Map<String, ChapterInfo>,
) {
    /** The chapter and in-chapter fraction holding book character [charCount], or null for an empty book. */
    fun resolveCharacterPosition(charCount: Int): Pair<Int, Double>? {
        val clamped = maxOf(0, minOf(charCount, characterCount - 1))
        for (chapter in chapterInfo.values.sortedBy { it.currentTotal }) {
            if (chapter.chapterCount <= 0) continue
            val start = chapter.currentTotal
            val end = start + chapter.chapterCount
            if (clamped >= start && clamped < end) {
                return Pair(chapter.chapterIndex, (clamped - start).toDouble() / chapter.chapterCount)
            }
        }
        return null
    }
}

object KosyncBookIndex {
    /** The book's character index, computing and caching it the first time it is needed. */
    fun loadOrBuild(directory: File, books: KosyncBookSource): BookInfo? {
        BookStorage.load<BookInfo>(directory, FileNames.bookinfo)?.let { return it }
        val epub = books.load(directory) ?: return null
        return try {
            var runningTotal = 0
            val chapters = linkedMapOf<String, ChapterInfo>()
            for (index in epub.linearSpineItems.indices) {
                val chapterCount = epub.getChapterCharacters(index)
                chapters[index.toString()] =
                    ChapterInfo(chapterIndex = index, currentTotal = runningTotal, chapterCount = chapterCount)
                runningTotal += chapterCount
            }
            BookInfo(characterCount = runningTotal, chapterInfo = chapters)
                .also { BookStorage.save(it, directory, FileNames.bookinfo) }
        } catch (e: Exception) {
            null
        }
    }
}
