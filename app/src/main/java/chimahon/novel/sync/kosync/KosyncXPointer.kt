package chimahon.novel.sync.kosync

import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.math.ceil

/**
 * crengine xpointers as KOReader stores them for EPUBs:
 * `/body/DocFragment[7]/body/div[1]/p[5]/text()[1].12`. `DocFragment[n]` is the 1-based raw
 * spine index (crengine makes one fragment per spine item, even unparsable ones); the rest is an
 * element path with 1-based same-name sibling indexes, an optional `text()` step and an
 * optional `.offset` in code points.
 *
 * KOReader positions a book with this string and nothing else (`GotoXPointer`); a string crengine
 * cannot resolve means page 1. So every pointer written here is either an element path that
 * exists in the chapter DOM or the chapter start.
 */
object KosyncXPointer {
    fun chapterStart(rawSpineIndex: Int): String = "/body/DocFragment[${rawSpineIndex + 1}]/body"

    /** The 0-based raw spine index named by [xpointer], or null when it is not a DocFragment pointer. */
    fun spineIndex(xpointer: String): Int? =
        DocFragmentRegex.find(xpointer)?.let { match ->
            (match.groupValues[1].toIntOrNull() ?: 1) - 1
        }?.takeIf { it >= 0 }

    /**
     * Pointer for the reader position [progress] (0..1) inside a chapter, mirroring how the
     * reader's `restoreProgress` picks its target text node: the node holding character
     * `ceil(total × progress)`. The pointer anchors on the nearest enclosing leaf block whose
     * ancestor chain is made only of container tags; anything less certain falls back up the
     * chain, down to the chapter start.
     */
    fun forProgress(rawSpineIndex: Int, body: Element, progress: Double): String {
        val prefix = chapterStart(rawSpineIndex)
        val runs = KosyncChapterDom.textNodes(body)
        val total = runs.sumOf { it.chars }
        if (progress <= 0.0 || total <= 0) return prefix
        val target = ceil(total * progress).toInt()
        var running = 0
        var targetNode: Node? = null
        var lastNode: Node? = null
        for (run in runs) {
            if (run.chars > 0) lastNode = run.node
            if (running + run.chars > target) {
                targetNode = run.node
                break
            }
            running += run.chars
        }
        val anchor = anchorElement(body, targetNode ?: lastNode ?: return prefix)
        return prefix + elementPath(body, anchor)
    }

    /**
     * Where [xpointer] points inside this chapter, all as fractions of the chapter's counted
     * characters: [fraction] is the position, [blockStart]..[blockEnd] the range of the element
     * the pointer names (equal to [fraction] when it names a text offset).
     */
    data class Resolved(val fraction: Double, val blockStart: Double, val blockEnd: Double)

    /** Null when the pointer is not a DocFragment pointer or its path does not exist in [body]. */
    fun resolve(xpointer: String, body: Element): Resolved? {
        val steps = parseSteps(xpointer) ?: return null
        var current: Node = body
        var textOffset = -1
        for ((index, step) in steps.withIndex()) {
            val isLast = index == steps.lastIndex
            if (step.isText) {
                val texts = KosyncChapterDom.children(current).filter(KosyncChapterDom::isText)
                val text = texts.getOrNull(step.index - 1)
                if (text == null || !isLast) break
                current = text
                textOffset = step.offset
                break
            }
            current = KosyncChapterDom.childElements(current)
                .filter { KosyncChapterDom.tagName(it) == step.name }
                .getOrNull(step.index - 1)
                ?: return null
        }
        val runs = KosyncChapterDom.textNodes(body)
        val total = runs.sumOf { it.chars }
        if (total <= 0) return Resolved(0.0, 0.0, 0.0)
        val before = charsBefore(body, runs, current, textOffset) ?: return null
        val fraction = (before.toDouble() / total).coerceIn(0.0, 1.0)
        if (KosyncChapterDom.isText(current)) return Resolved(fraction, fraction, fraction)
        val inside = charsWithin(runs, current)
        return Resolved(fraction, fraction, ((before + inside).toDouble() / total).coerceIn(0.0, 1.0))
    }

    private fun charsBefore(body: Element, runs: List<KosyncChapterDom.TextRun>, target: Node, textOffset: Int): Int? {
        if (KosyncChapterDom.isText(target)) {
            var running = 0
            for (run in runs) {
                if (run.node === target) {
                    val text = run.node.nodeValue.orEmpty()
                    val prefix = if (textOffset >= 0) codePointPrefix(text, textOffset) else ""
                    return running + KosyncTextSemantics.countChars(prefix)
                }
                running += run.chars
            }
            // Text inside furigana is not walked; anchor on its element instead.
            return charsBefore(body, runs, target.parentNode ?: return null, -1)
        }
        val counted = runs.associate { it.node to it.chars }
        var running = 0
        var found = false
        fun visit(node: Node) {
            if (found) return
            if (node === target) {
                found = true
                return
            }
            if (KosyncChapterDom.isText(node)) {
                running += counted[node] ?: 0
                return
            }
            KosyncChapterDom.children(node).forEach(::visit)
        }
        visit(body)
        return if (found) running else null
    }

    private fun charsWithin(runs: List<KosyncChapterDom.TextRun>, element: Node): Int {
        val counted = runs.associate { it.node to it.chars }
        var sum = 0
        fun visit(node: Node) {
            if (KosyncChapterDom.isText(node)) {
                sum += counted[node] ?: 0
                return
            }
            KosyncChapterDom.children(node).forEach(::visit)
        }
        visit(element)
        return sum
    }

    private fun codePointPrefix(text: String, codePoints: Int): String {
        var end = 0
        var count = 0
        while (end < text.length && count < codePoints) {
            end += Character.charCount(text.codePointAt(end))
            count++
        }
        return text.substring(0, end)
    }

    private fun anchorElement(body: Element, textNode: Node): Element {
        val chain = ArrayList<Element>()
        var node: Node? = textNode.parentNode
        while (node != null && node !== body) {
            (node as? Element)?.let(chain::add)
            node = node.parentNode
        }
        if (node == null) return body
        chain.reverse()
        var anchor: Element = body
        for (element in chain) {
            when (KosyncChapterDom.tagName(element)) {
                in LeafBlockTags -> return element
                in ContainerTags -> anchor = element
                else -> return anchor
            }
        }
        return anchor
    }

    private fun elementPath(body: Element, element: Element): String {
        if (element === body) return ""
        val segments = ArrayList<String>()
        var current: Element = element
        while (current !== body) {
            val parent = current.parentNode as? Element ?: return ""
            val name = KosyncChapterDom.tagName(current)
            val index = KosyncChapterDom.childElements(parent)
                .filter { KosyncChapterDom.tagName(it) == name }
                .indexOfFirst { it === current } + 1
            segments.add("$name[$index]")
            current = parent
        }
        return segments.reversed().joinToString(separator = "/", prefix = "/")
    }

    private data class Step(val name: String, val index: Int, val isText: Boolean, val offset: Int)

    /** The steps after `/body/DocFragment[n]/body`; empty for a bare fragment pointer; null when not a pointer. */
    private fun parseSteps(xpointer: String): List<Step>? {
        val match = DocFragmentRegex.find(xpointer) ?: return null
        var rest = xpointer.substring(match.range.last + 1)
        if (rest.isEmpty()) return emptyList()
        val bodyMatch = BodyStepRegex.find(rest) ?: return null
        rest = rest.substring(bodyMatch.range.last + 1)
        var offset = -1
        val dot = rest.lastIndexOf('.')
        if (dot >= 0 && rest.substring(dot + 1).let { it.isNotEmpty() && it.all(Char::isDigit) }) {
            offset = rest.substring(dot + 1).toInt()
            rest = rest.substring(0, dot)
        }
        val steps = rest.split('/').filter { it.isNotEmpty() }.map { segment ->
            val stepMatch = StepRegex.matchEntire(segment) ?: return null
            val name = stepMatch.groupValues[1].lowercase()
            val index = stepMatch.groupValues[2].toIntOrNull() ?: 1
            Step(name = name, index = index, isText = name == "text()", offset = -1)
        }
        if (steps.isEmpty()) return steps
        return steps.dropLast(1) + steps.last().copy(offset = offset)
    }

    private val DocFragmentRegex = Regex("^/body/DocFragment(?:\\[(\\d+)\\])?")
    private val BodyStepRegex = Regex("^/body(?:\\[1\\])?")
    private val StepRegex = Regex("^([A-Za-z][A-Za-z0-9_:-]*|text\\(\\))(?:\\[(\\d+)\\])?$")

    private val LeafBlockTags = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "figcaption", "hr")
    private val ContainerTags = setOf(
        "div", "section", "article", "aside", "blockquote", "ul", "ol", "li", "dl", "dd", "dt",
        "figure", "header", "footer", "main", "nav", "center",
    )
}
