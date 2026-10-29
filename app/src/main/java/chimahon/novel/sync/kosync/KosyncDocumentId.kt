package chimahon.novel.sync.kosync

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Document identity as KOReader's kosync plugin computes it (`frontend/util.lua`,
 * `partialMD5`): twelve 1 KiB samples at fixed offsets fed into one MD5, stopping at the first
 * sample that starts at or past the end of the file; a short last sample is fed as read. The
 * digest is 32 lowercase hex characters.
 *
 * KOReader derives the offsets as `lshift(1024, 2 * i)` for i = -1..10. Under LuaJIT the i = -1
 * step wraps to 0 instead of giving 256, so the first sample is at offset 0. The offsets are
 * listed literally so nobody "fixes" the shift; see the test vectors.
 */
object KosyncDocumentId {
    fun partialMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(SAMPLE_SIZE)
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            for (offset in SAMPLE_OFFSETS) {
                if (offset >= length) break
                input.seek(offset)
                val read = input.read(buffer, 0, SAMPLE_SIZE)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val SAMPLE_SIZE = 1024

    private val SAMPLE_OFFSETS: List<Long> = listOf(
        0L,
        1024L,
        4096L,
        16384L,
        65536L,
        262144L,
        1048576L,
        4194304L,
        16777216L,
        67108864L,
        268435456L,
        1073741824L,
    )
}
