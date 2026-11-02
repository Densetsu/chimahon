package chimahon.novel.sync.kosync

import chimahon.novel.data.epub.EpubBook

/**
 * crengine builds one `DocFragment` per spine `itemref`, including the non-linear ones the
 * reader hides, so DocFragment numbers follow the raw spine while the reader's chapter index
 * counts linear items only.
 */
internal fun EpubBook.rawSpineIndex(chapterIndex: Int): Int {
    var linear = 0
    spine.items.forEachIndexed { raw, item ->
        if (item.linear) {
            if (linear == chapterIndex) return raw
            linear++
        }
    }
    return chapterIndex
}

/** The reader chapter a raw spine position maps to; [exact] is false when the item itself is hidden. */
internal data class SpineTarget(val chapterIndex: Int, val exact: Boolean)

/**
 * The chapter for a raw spine position: the item itself when it is linear, otherwise the next
 * linear item (or the last one when none follows). Null outside the spine or with no linear items.
 */
internal fun EpubBook.spineTarget(rawIndex: Int): SpineTarget? {
    val items = spine.items
    if (rawIndex !in items.indices) return null
    val before = items.take(rawIndex).count { it.linear }
    if (items[rawIndex].linear) return SpineTarget(before, exact = true)
    val linearCount = items.count { it.linear }
    if (linearCount == 0) return null
    return SpineTarget(before.coerceAtMost(linearCount - 1), exact = false)
}
