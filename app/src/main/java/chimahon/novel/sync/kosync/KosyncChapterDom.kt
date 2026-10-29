package chimahon.novel.sync.kosync

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Chapter XHTML parsed as XML, i.e. the same tree the reader WebView builds (chapters are served
 * as `application/xhtml+xml`), and the same element tree crengine builds under
 * `/body/DocFragment[n]/body`. Used only for XPointer mapping.
 */
object KosyncChapterDom {
    fun parseBody(xhtml: String): Element? = runCatching {
        val builder = documentBuilderFactory().newDocumentBuilder()
        val document = builder.parse(InputSource(StringReader(prepareXml(xhtml))))
        findElement(document.documentElement, "body")
    }.getOrNull()

    /** An XML parser for documents from outside the app: no doctype, no external entities. */
    private fun documentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }

    fun tagName(node: Node): String = node.nodeName.substringAfter(':').lowercase()

    fun isText(node: Node): Boolean =
        node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE

    fun childElements(parent: Node): List<Element> =
        children(parent).filterIsInstance<Element>()

    fun children(parent: Node): List<Node> {
        val list = parent.childNodes
        return List(list.length) { list.item(it) }
    }

    /**
     * Text nodes in document order, skipping furigana (`rt`/`rp`), exactly like the reader's
     * `createWalker`. Each entry carries the ttu character count of the node.
     */
    fun textNodes(body: Element): List<TextRun> {
        val runs = ArrayList<TextRun>()
        fun visit(node: Node, insideFurigana: Boolean) {
            if (isText(node)) {
                if (!insideFurigana) runs.add(TextRun(node, KosyncTextSemantics.countChars(node.nodeValue.orEmpty())))
                return
            }
            if (node.nodeType != Node.ELEMENT_NODE) return
            val tag = tagName(node)
            val furigana = insideFurigana || tag == "rt" || tag == "rp"
            children(node).forEach { visit(it, furigana) }
        }
        visit(body, false)
        return runs
    }

    data class TextRun(val node: Node, val chars: Int)

    private fun findElement(root: Element?, name: String): Element? {
        if (root == null) return null
        if (tagName(root) == name) return root
        childElements(root).forEach { child ->
            findElement(child, name)?.let { return it }
        }
        return null
    }

    // Strip the DOCTYPE (external DTD loading is disabled) and turn XHTML named entities into
    // numeric references so a plain XML parser accepts them.
    private fun prepareXml(xhtml: String): String {
        val withoutDoctype = DoctypeRegex.replace(xhtml.removePrefix("\uFEFF"), "")
        return EntityRegex.replace(withoutDoctype) { match ->
            val name = match.groupValues[1]
            when {
                name in XmlEntities -> match.value
                else -> XhtmlEntities[name]?.let { "&#x${it.toString(16)};" } ?: "&amp;$name;"
            }
        }
    }

    private val DoctypeRegex = Regex("<!DOCTYPE[^\\[>]*(\\[[^\\]]*\\])?\\s*>", RegexOption.IGNORE_CASE)
    private val EntityRegex = Regex("&([A-Za-z][A-Za-z0-9]*);")
    private val XmlEntities = setOf("amp", "lt", "gt", "quot", "apos")

    private val XhtmlEntities: Map<String, Int> = mapOf(
        "AElig" to 0xC6, "Aacute" to 0xC1, "Acirc" to 0xC2, "Agrave" to 0xC0, "Alpha" to 0x391, "Aring" to 0xC5,
        "Atilde" to 0xC3, "Auml" to 0xC4, "Beta" to 0x392, "Ccedil" to 0xC7, "Chi" to 0x3A7, "Dagger" to 0x2021,
        "Delta" to 0x394, "ETH" to 0xD0, "Eacute" to 0xC9, "Ecirc" to 0xCA, "Egrave" to 0xC8, "Epsilon" to 0x395,
        "Eta" to 0x397, "Euml" to 0xCB, "Gamma" to 0x393, "Iacute" to 0xCD, "Icirc" to 0xCE, "Igrave" to 0xCC,
        "Iota" to 0x399, "Iuml" to 0xCF, "Kappa" to 0x39A, "Lambda" to 0x39B, "Mu" to 0x39C, "Ntilde" to 0xD1,
        "Nu" to 0x39D, "OElig" to 0x152, "Oacute" to 0xD3, "Ocirc" to 0xD4, "Ograve" to 0xD2, "Omega" to 0x3A9,
        "Omicron" to 0x39F, "Oslash" to 0xD8, "Otilde" to 0xD5, "Ouml" to 0xD6, "Phi" to 0x3A6, "Pi" to 0x3A0,
        "Prime" to 0x2033, "Psi" to 0x3A8, "Rho" to 0x3A1, "Scaron" to 0x160, "Sigma" to 0x3A3, "THORN" to 0xDE,
        "Tau" to 0x3A4, "Theta" to 0x398, "Uacute" to 0xDA, "Ucirc" to 0xDB, "Ugrave" to 0xD9, "Upsilon" to 0x3A5,
        "Uuml" to 0xDC, "Xi" to 0x39E, "Yacute" to 0xDD, "Yuml" to 0x178, "Zeta" to 0x396, "aacute" to 0xE1,
        "acirc" to 0xE2, "acute" to 0xB4, "aelig" to 0xE6, "agrave" to 0xE0, "alefsym" to 0x2135, "alpha" to 0x3B1,
        "and" to 0x2227, "ang" to 0x2220, "aring" to 0xE5, "asymp" to 0x2248, "atilde" to 0xE3, "auml" to 0xE4,
        "bdquo" to 0x201E, "beta" to 0x3B2, "brvbar" to 0xA6, "bull" to 0x2022, "cap" to 0x2229, "ccedil" to 0xE7,
        "cedil" to 0xB8, "cent" to 0xA2, "chi" to 0x3C7, "circ" to 0x2C6, "clubs" to 0x2663, "cong" to 0x2245,
        "copy" to 0xA9, "crarr" to 0x21B5, "cup" to 0x222A, "curren" to 0xA4, "dArr" to 0x21D3, "dagger" to 0x2020,
        "darr" to 0x2193, "deg" to 0xB0, "delta" to 0x3B4, "diams" to 0x2666, "divide" to 0xF7, "eacute" to 0xE9,
        "ecirc" to 0xEA, "egrave" to 0xE8, "empty" to 0x2205, "emsp" to 0x2003, "ensp" to 0x2002, "epsilon" to 0x3B5,
        "equiv" to 0x2261, "eta" to 0x3B7, "eth" to 0xF0, "euml" to 0xEB, "euro" to 0x20AC, "exist" to 0x2203,
        "fnof" to 0x192, "forall" to 0x2200, "frac12" to 0xBD, "frac14" to 0xBC, "frac34" to 0xBE, "frasl" to 0x2044,
        "gamma" to 0x3B3, "ge" to 0x2265, "hArr" to 0x21D4, "harr" to 0x2194, "hearts" to 0x2665, "hellip" to 0x2026,
        "iacute" to 0xED, "icirc" to 0xEE, "iexcl" to 0xA1, "igrave" to 0xEC, "image" to 0x2111, "infin" to 0x221E,
        "int" to 0x222B, "iota" to 0x3B9, "iquest" to 0xBF, "isin" to 0x2208, "iuml" to 0xEF, "kappa" to 0x3BA,
        "lArr" to 0x21D0, "lambda" to 0x3BB, "lang" to 0x2329, "laquo" to 0xAB, "larr" to 0x2190, "lceil" to 0x2308,
        "ldquo" to 0x201C, "le" to 0x2264, "lfloor" to 0x230A, "lowast" to 0x2217, "loz" to 0x25CA, "lrm" to 0x200E,
        "lsaquo" to 0x2039, "lsquo" to 0x2018, "macr" to 0xAF, "mdash" to 0x2014, "micro" to 0xB5, "middot" to 0xB7,
        "minus" to 0x2212, "mu" to 0x3BC, "nabla" to 0x2207, "nbsp" to 0xA0, "ndash" to 0x2013, "ne" to 0x2260,
        "ni" to 0x220B, "not" to 0xAC, "notin" to 0x2209, "nsub" to 0x2284, "ntilde" to 0xF1, "nu" to 0x3BD,
        "oacute" to 0xF3, "ocirc" to 0xF4, "oelig" to 0x153, "ograve" to 0xF2, "oline" to 0x203E, "omega" to 0x3C9,
        "omicron" to 0x3BF, "oplus" to 0x2295, "or" to 0x2228, "ordf" to 0xAA, "ordm" to 0xBA, "oslash" to 0xF8,
        "otilde" to 0xF5, "otimes" to 0x2297, "ouml" to 0xF6, "para" to 0xB6, "part" to 0x2202, "permil" to 0x2030,
        "perp" to 0x22A5, "phi" to 0x3C6, "pi" to 0x3C0, "piv" to 0x3D6, "plusmn" to 0xB1, "pound" to 0xA3,
        "prime" to 0x2032, "prod" to 0x220F, "prop" to 0x221D, "psi" to 0x3C8, "rArr" to 0x21D2, "radic" to 0x221A,
        "rang" to 0x232A, "raquo" to 0xBB, "rarr" to 0x2192, "rceil" to 0x2309, "rdquo" to 0x201D, "real" to 0x211C,
        "reg" to 0xAE, "rfloor" to 0x230B, "rho" to 0x3C1, "rlm" to 0x200F, "rsaquo" to 0x203A, "rsquo" to 0x2019,
        "sbquo" to 0x201A, "scaron" to 0x161, "sdot" to 0x22C5, "sect" to 0xA7, "shy" to 0xAD, "sigma" to 0x3C3,
        "sigmaf" to 0x3C2, "sim" to 0x223C, "spades" to 0x2660, "sub" to 0x2282, "sube" to 0x2286, "sum" to 0x2211,
        "sup" to 0x2283, "sup1" to 0xB9, "sup2" to 0xB2, "sup3" to 0xB3, "supe" to 0x2287, "szlig" to 0xDF,
        "tau" to 0x3C4, "there4" to 0x2234, "theta" to 0x3B8, "thetasym" to 0x3D1, "thinsp" to 0x2009, "thorn" to 0xFE,
        "tilde" to 0x2DC, "times" to 0xD7, "trade" to 0x2122, "uArr" to 0x21D1, "uacute" to 0xFA, "uarr" to 0x2191,
        "ucirc" to 0xFB, "ugrave" to 0xF9, "uml" to 0xA8, "upsih" to 0x3D2, "upsilon" to 0x3C5, "uuml" to 0xFC,
        "weierp" to 0x2118, "xi" to 0x3BE, "yacute" to 0xFD, "yen" to 0xA5, "yuml" to 0xFF, "zeta" to 0x3B6,
        "zwj" to 0x200D, "zwnj" to 0x200C,
    )
}
