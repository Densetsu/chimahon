package chimahon.novel.sync.kosync

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A loopback HTTP/1.1 server for client tests: one request per connection, answered by [handler].
 * Every request is kept in [requests] so a test can assert on what the client sent.
 */
class TinyHttpServer(private val handler: (Request) -> Response) : AutoCloseable {
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    class Response(val status: Int, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap()) {
        constructor(status: Int, body: String) : this(status, body.toByteArray())
    }

    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: return@thread
                runCatching {
                    connection.use { open ->
                        val request = read(open.getInputStream().buffered()) ?: return@use
                        requests += request
                        val response = handler(request)
                        val head = buildString {
                            append("HTTP/1.1 ${response.status} ${if (response.status < 400) "OK" else "Error"}\r\n")
                            append("Content-Length: ${response.body.size}\r\n")
                            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                            append("Connection: close\r\n\r\n")
                        }
                        open.getOutputStream().apply {
                            write(head.toByteArray(Charsets.ISO_8859_1))
                            if (request.method != "HEAD") write(response.body)
                            flush()
                        }
                    }
                }
            }
        }
    }

    override fun close() = socket.close()

    private fun read(input: InputStream): Request? {
        val requestLine = readLine(input)?.split(" ")?.takeIf { it.size >= 2 } ?: return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isNullOrEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val body = ByteArray(headers["content-length"]?.toIntOrNull() ?: 0)
        var read = 0
        while (read < body.size) {
            val count = input.read(body, read, body.size - read)
            if (count < 0) break
            read += count
        }
        return Request(requestLine[0], URLDecoder.decode(requestLine[1], "UTF-8"), headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
    }
}
