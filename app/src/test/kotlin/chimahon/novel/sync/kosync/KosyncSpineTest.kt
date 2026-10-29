package chimahon.novel.sync.kosync

import chimahon.novel.data.epub.EpubBook
import chimahon.novel.data.epub.EpubSpine
import chimahon.novel.data.epub.SpineItem
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KosyncSpineTest {
    // crengine numbers a DocFragment per spine itemref, but the reader only walks the linear
    // ones, so a non-linear item in the middle shifts the two indexes apart.
    private val book = EpubBook(
        spine = EpubSpine(
            items = listOf(
                SpineItem(idref = "cover", linear = true),
                SpineItem(idref = "advert", linear = false),
                SpineItem(idref = "chapter1", linear = true),
                SpineItem(idref = "chapter2", linear = true),
                SpineItem(idref = "colophon", linear = false),
            ),
        ),
    )

    @Test
    fun `maps reader chapters onto raw spine positions`() {
        book.rawSpineIndex(0) shouldBe 0
        book.rawSpineIndex(1) shouldBe 2
        book.rawSpineIndex(2) shouldBe 3
    }

    @Test
    fun `maps raw spine positions back to reader chapters`() {
        book.spineTarget(0) shouldBe SpineTarget(chapterIndex = 0, exact = true)
        book.spineTarget(2) shouldBe SpineTarget(chapterIndex = 1, exact = true)
        book.spineTarget(3) shouldBe SpineTarget(chapterIndex = 2, exact = true)
    }

    @Test
    fun `a non-linear item lands on the next linear chapter, inexactly`() {
        book.spineTarget(1) shouldBe SpineTarget(chapterIndex = 1, exact = false)
        // A trailing non-linear item has no next chapter: stay on the last one.
        book.spineTarget(4) shouldBe SpineTarget(chapterIndex = 2, exact = false)
    }

    @Test
    fun `positions outside the spine have no chapter`() {
        book.spineTarget(5).shouldBeNull()
        book.spineTarget(-1).shouldBeNull()
        EpubBook().spineTarget(0).shouldBeNull()
    }

    @Test
    fun `round trips every chapter`() {
        book.linearSpineItems.indices.forEach { chapter ->
            book.spineTarget(book.rawSpineIndex(chapter)) shouldBe SpineTarget(chapter, exact = true)
        }
    }
}
