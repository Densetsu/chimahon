package chimahon.novel.sync.kosync

/**
 * The ttu character alphabet, the same class `EpubBook.countCharacters` filters with. Reader
 * progress converts to and from character counts through that alphabet, so the xpointer mapping
 * has to count the same characters or its anchor would drift from the position the reader
 * reports.
 */
object KosyncTextSemantics {
    private val NotCounted =
        Regex("[^0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\\p{IsHan}\\p{IsHangul}]")

    fun countChars(text: CharSequence): Int {
        val filtered = NotCounted.replace(text.toString(), "")
        return filtered.codePointCount(0, filtered.length)
    }
}
