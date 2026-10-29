package chimahon.novel.sync.kosync

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KosyncXPointerTest {
    private val chapter = """
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>c</title></head>
        <body>
          <div class="main">
            <h1>第一章</h1>
            <p>あいうえお、かきくけこ。</p>
            <p><ruby>漢字<rt>かんじ</rt></ruby>を書く&nbsp;test one two</p>
            <div><span>さしすせそ</span><p>たちつてと</p></div>
            <table><tr><td>なにぬねの</td></tr></table>
          </div>
        </body></html>
    """.trimIndent()

    // Counted characters: h1 3, p[1] 10, p[2] 2 + 3 + 10 (furigana skipped, punctuation and nbsp
    // skipped), span 5, nested p 5, td 5 = 43.
    private val total = 43.0

    private val body get() = checkNotNull(KosyncChapterDom.parseBody(chapter))

    @Test
    fun `counts characters like the reader`() {
        KosyncChapterDom.textNodes(body).sumOf { it.chars } shouldBe 43
    }

    @Test
    fun `zero progress and an empty chapter point at the chapter start`() {
        KosyncXPointer.forProgress(2, body, 0.0) shouldBe "/body/DocFragment[3]/body"
        val empty = checkNotNull(KosyncChapterDom.parseBody("<html><body><div><img src='x.png'/></div></body></html>"))
        KosyncXPointer.forProgress(0, empty, 0.5) shouldBe "/body/DocFragment[1]/body"
    }

    @Test
    fun `anchors on the block containing the target character`() {
        KosyncXPointer.forProgress(0, body, 8.0 / total) shouldBe "/body/DocFragment[1]/body/div[1]/p[1]"
        KosyncXPointer.forProgress(0, body, 14.0 / total) shouldBe "/body/DocFragment[1]/body/div[1]/p[2]"
        // An inline span under a div anchors on the div; a nested p is a leaf block.
        KosyncXPointer.forProgress(0, body, 28.0 / total) shouldBe "/body/DocFragment[1]/body/div[1]/div[1]"
        KosyncXPointer.forProgress(0, body, 33.0 / total) shouldBe "/body/DocFragment[1]/body/div[1]/div[1]/p[1]"
        // Table structure is uncertain in crengine, so fall back to the enclosing div.
        KosyncXPointer.forProgress(0, body, 40.0 / total) shouldBe "/body/DocFragment[1]/body/div[1]"
        KosyncXPointer.forProgress(0, body, 1.0) shouldBe "/body/DocFragment[1]/body/div[1]"
    }

    @Test
    fun `resolves element and text pointers`() {
        resolveFraction("/body/DocFragment[1]/body") shouldBe (0.0 plusOrMinus 1e-9)
        resolveFraction("/body/DocFragment[1]") shouldBe (0.0 plusOrMinus 1e-9)
        // A missing [1] is the same step as an explicit one.
        resolveFraction("/body/DocFragment[1]/body/div/p[2]") shouldBe (13.0 / total plusOrMinus 1e-9)
        resolveFraction("/body/DocFragment[1]/body[1]/div[1]/p[2]") shouldBe (13.0 / total plusOrMinus 1e-9)
        // text()[1] is the first direct text child of p[2]: the run after the ruby element.
        resolveFraction("/body/DocFragment[1]/body/div[1]/p[2]/text()[1].0") shouldBe (15.0 / total plusOrMinus 1e-9)
        // Offset three code points into the first paragraph's text.
        resolveFraction("/body/DocFragment[1]/body/div[1]/p[1]/text().3") shouldBe (6.0 / total plusOrMinus 1e-9)
    }

    @Test
    fun `reports the character range of the anchored block`() {
        val p2 = checkNotNull(KosyncXPointer.resolve("/body/DocFragment[1]/body/div[1]/p[2]", body))
        p2.blockStart shouldBe (13.0 / total plusOrMinus 1e-9)
        p2.blockEnd shouldBe (28.0 / total plusOrMinus 1e-9)
        val text = checkNotNull(KosyncXPointer.resolve("/body/DocFragment[1]/body/div[1]/p[1]/text().3", body))
        text.blockStart shouldBe text.fraction
        text.blockEnd shouldBe text.fraction
    }

    @Test
    fun `rejects pointers that are not positions in this chapter`() {
        KosyncXPointer.resolve("/body/DocFragment[1]/body/div[1]/p[9]", body).shouldBeNull()
        KosyncXPointer.resolve("/body/DocFragment[2]/body/section[1]", body).shouldBeNull()
        KosyncXPointer.resolve("Chapter 5", body).shouldBeNull()
        KosyncXPointer.resolve("42", body).shouldBeNull()
        KosyncXPointer.resolve("", body).shouldBeNull()
    }

    @Test
    fun `a resolved fraction is a fixed point of the mapping`() {
        // Re-encoding the fraction a pointer resolves to gives a pointer that resolves to the same
        // fraction. (An anchor on a container resolves to the container's start, which may re-encode
        // as the chapter start: the same position, so the same fraction, not the same string.)
        for (step in 0..20) {
            val pointer = KosyncXPointer.forProgress(4, body, step / 20.0)
            val resolved = KosyncXPointer.resolve(pointer, body).shouldNotBeNull()
            val again = KosyncXPointer.forProgress(4, body, resolved.fraction)
            KosyncXPointer.resolve(again, body).shouldNotBeNull().fraction shouldBe (resolved.fraction plusOrMinus 1e-9)
        }
        KosyncXPointer.forProgress(4, body, 0.5) shouldBe "/body/DocFragment[5]/body/div[1]/p[2]"
        KosyncXPointer.resolve("/body/DocFragment[5]/body/div[1]/p[2]", body).shouldNotBeNull().fraction shouldBe
            (13.0 / total plusOrMinus 1e-9)
    }

    @Test
    fun `parses the spine index from the DocFragment step`() {
        KosyncXPointer.spineIndex("/body/DocFragment[11]/body/div/p[5]/text().123") shouldBe 10
        KosyncXPointer.spineIndex("/body/DocFragment/body") shouldBe 0
        KosyncXPointer.spineIndex("12").shouldBeNull()
        KosyncXPointer.spineIndex("Chapter 5").shouldBeNull()
        KosyncXPointer.spineIndex("").shouldBeNull()
    }

    @Test
    fun `malformed chapter parses to null`() {
        KosyncChapterDom.parseBody("<html><body><p>x</p></body></html>").shouldNotBeNull()
        KosyncChapterDom.parseBody("<html><body><p>x</body></html>").shouldBeNull()
        KosyncChapterDom.parseBody("<html><body><br><p a='1' a='2'>x</p></body></html>").shouldBeNull()
    }

    private fun resolveFraction(pointer: String): Double =
        checkNotNull(KosyncXPointer.resolve(pointer, body)) { "did not resolve: $pointer" }.fraction
}
