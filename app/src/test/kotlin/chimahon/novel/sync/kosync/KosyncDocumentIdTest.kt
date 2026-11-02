package chimahon.novel.sync.kosync

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KosyncDocumentIdTest {
    @TempDir
    lateinit var tempDir: File

    // Expected digests were produced by running KOReader's own util.partialMD5 under LuaJIT on
    // files where byte i equals i mod 251 (design doc, "Document identity"), and reproduced with
    // an independent Python port of the sampling.
    @Test
    fun `partial md5 matches KOReader on files where byte i is i mod 251`() {
        KosyncDocumentId.partialMd5(pattern(700)) shouldBe "7496cac5ac164e11c6798f950cbaa0c5"
        KosyncDocumentId.partialMd5(pattern(1024)) shouldBe "9ee0a0e0c0bc0f1ff29d663d1fdf0743"
        KosyncDocumentId.partialMd5(pattern(1025)) shouldBe "3f3789452b88cb32b8cbfbafe715e29a"
        KosyncDocumentId.partialMd5(pattern(100_000)) shouldBe "f34024643c82aec13b2fd5eb2842658a"
        KosyncDocumentId.partialMd5(pattern(5_000_000)) shouldBe "94c784935ab8dda597646e9bc916bbad"
        KosyncDocumentId.partialMd5(pattern(70_000_000)) shouldBe "a5637682c9ec71d98efd7ce292670793"
    }

    @Test
    fun `only the sampled windows affect the id`() {
        // In a 5000-byte file the samples cover 0-1023, 1024-2047 and 4096-4999: byte 3000 is never read.
        val baseline = KosyncDocumentId.partialMd5(pattern(5000))
        KosyncDocumentId.partialMd5(pattern(5000, flipAt = 3000)) shouldBe baseline
        KosyncDocumentId.partialMd5(pattern(5000, flipAt = 100)) shouldNotBe baseline
        KosyncDocumentId.partialMd5(pattern(5000, flipAt = 4500)) shouldNotBe baseline
    }

    @Test
    fun `an empty file is the md5 of nothing`() {
        KosyncDocumentId.partialMd5(pattern(0)) shouldBe "d41d8cd98f00b204e9800998ecf8427e"
    }

    private fun pattern(size: Int, flipAt: Int? = null): File = File(tempDir, "f-$size-$flipAt").apply {
        outputStream().buffered(1 shl 20).use { out ->
            val chunk = ByteArray(1 shl 20)
            var i = 0
            while (i < size) {
                val n = minOf(chunk.size, size - i)
                for (j in 0 until n) chunk[j] = ((i + j) % 251).toByte()
                if (flipAt != null && flipAt >= i && flipAt < i + n) {
                    chunk[flipAt - i] = (chunk[flipAt - i].toInt() xor 0xff).toByte()
                }
                out.write(chunk, 0, n)
                i += n
            }
        }
    }
}
