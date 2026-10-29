package chimahon.novel.sync.kosync

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs

/**
 * Runs the xpointer mapping over real chapters: every pointer it would push must have the shape
 * crengine accepts, resolve on our own DOM and be a fixed point of the mapping. Point `KOSYNC_CORPUS_DIR` at a folder
 * of EPUBs to run it; it is skipped otherwise.
 */
@EnabledIfEnvironmentVariable(named = "KOSYNC_CORPUS_DIR", matches = ".+")
class KosyncCorpusTest {
    private val pointerShape = Regex(
        "^/body/DocFragment\\[\\d+\\]/body" +
            "(/(p|h[1-6]|pre|figcaption|hr|" +
            "div|section|article|aside|blockquote|ul|ol|li|dl|dd|dt|figure|header|footer|main|nav|center)" +
            "\\[\\d+\\])*$",
    )

    @Test
    fun `every pointer emitted for the corpus has the crengine shape, resolves and is a fixed point`() {
        val corpus = File(System.getenv("KOSYNC_CORPUS_DIR"))
        val epubs = corpus.walkTopDown().filter { it.isFile && it.extension.equals("epub", ignoreCase = true) }.toList()
        (epubs.isNotEmpty()) shouldBe true

        var chapters = 0
        var pointers = 0
        var notXml = 0
        val notXmlNames = mutableListOf<String>()
        val problems = mutableListOf<String>()
        for (epub in epubs) {
            ZipFile(epub).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter {
                        !it.isDirectory && it.name.substringAfterLast('.').lowercase() in setOf("xhtml", "html", "htm")
                    }
                for (entry in entries) {
                    val xhtml = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                    val body = KosyncChapterDom.parseBody(xhtml)
                    if (body == null) {
                        notXml++
                        notXmlNames += "${epub.name}!${entry.name}"
                        continue
                    }
                    chapters++
                    for (step in 0..100) {
                        val pointer = KosyncXPointer.forProgress(0, body, step / 100.0)
                        pointers++
                        val where = "${epub.name}!${entry.name}@$step"
                        if (!pointerShape.matches(pointer)) {
                            problems += "$where: shape $pointer"
                            continue
                        }
                        val resolved = KosyncXPointer.resolve(pointer, body)
                        if (resolved == null) {
                            problems += "$where: does not resolve $pointer"
                            continue
                        }
                        val again = KosyncXPointer.resolve(KosyncXPointer.forProgress(0, body, resolved.fraction), body)
                        if (again == null || abs(again.fraction - resolved.fraction) > 1e-9) {
                            problems += "$where: fixed point $pointer -> ${resolved.fraction} -> ${again?.fraction}"
                        }
                    }
                }
            }
        }
        println(
            "corpus: ${epubs.size} EPUBs, $chapters chapters, $pointers pointers, ${problems.size} problems, " +
                "$notXml chapters not XML",
        )
        notXmlNames.take(20).forEach { println("not XML: $it") }
        problems.take(20).forEach(::println)
        problems shouldBe emptyList()
    }
}
