package chimahon.novel.sync.kosync

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class KosyncClientTest {
    private fun client(
        pullConnectTimeout: Duration = 3_000.milliseconds,
        pullReadTimeout: Duration = 4_000.milliseconds,
    ) = KosyncClient(OkHttpClient(), pullConnectTimeout = pullConnectTimeout, pullReadTimeout = pullReadTimeout)

    private fun credentials(serverUrl: String) = KosyncCredentials(
        serverUrl = serverUrl,
        username = "reader",
        userKey = "0123456789abcdef0123456789abcdef",
    )

    @Test
    fun `a pull gives up on a server that never answers`() {
        // The connection is accepted by the OS backlog and then nothing is ever written back.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silentServer ->
            val client = client(pullConnectTimeout = 300.milliseconds, pullReadTimeout = 300.milliseconds)
            val startedAt = System.nanoTime()
            assertThrows(IOException::class.java) {
                runBlocking {
                    client.getProgress(credentials("http://127.0.0.1:${silentServer.localPort}"), "document")
                }
            }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            // Far below the 15 s a push is allowed to wait.
            (elapsedMillis < 5_000) shouldBe true
        }
    }

    @Test
    fun `a pull reads the position and sends the KOReader headers`() = runBlocking<Unit> {
        TinyHttpServer {
            TinyHttpServer.Response(
                200,
                """{"document":"abc","progress":"/body/DocFragment[3]/body/p[2]/text().0",""" +
                    """"percentage":0.25,"device":"Kobo","device_id":"KOBO1","timestamp":1700000000}""",
            )
        }.use { server ->
            // A typed address without a scheme and with a trailing slash still resolves.
            val address = server.url.removePrefix("http://") + "/"
            val progress = client().getProgress(credentials(address), "abc")

            progress?.progress shouldBe "/body/DocFragment[3]/body/p[2]/text().0"
            progress?.percentage shouldBe 0.25
            progress?.device shouldBe "Kobo"
            progress?.deviceId shouldBe "KOBO1"
            progress?.timestamp shouldBe 1_700_000_000L

            val request = server.requests.single()
            "${request.method} ${request.path}" shouldBe "GET /syncs/progress/abc"
            request.header("Accept") shouldBe "application/vnd.koreader.v1+json"
            request.header("x-auth-user") shouldBe "reader"
            request.header("x-auth-key") shouldBe "0123456789abcdef0123456789abcdef"
        }
    }

    @Test
    fun `a document the server has never seen is not an error`() = runBlocking<Unit> {
        TinyHttpServer { TinyHttpServer.Response(404) }.use { server ->
            client().getProgress(credentials(server.url), "abc").shouldBeNull()
        }
        // Some servers answer 200 with an empty object instead.
        TinyHttpServer { TinyHttpServer.Response(200, "{}") }.use { server ->
            client().getProgress(credentials(server.url), "abc")?.percentage.shouldBeNull()
        }
    }

    @Test
    fun `odd JSON shapes are read as absent, and fractional timestamps are rounded down`() = runBlocking<Unit> {
        TinyHttpServer {
            TinyHttpServer.Response(
                200,
                """{"document":"abc","progress":{"x":1},"percentage":"0.5","device":null,"device_id":["a"],""" +
                    """"timestamp":1700000000.9}""",
            )
        }.use { server ->
            val progress = client().getProgress(credentials(server.url), "abc")

            progress?.progress.shouldBeNull()
            // A number sent as a string still reads as the number.
            progress?.percentage shouldBe 0.5
            progress?.device.shouldBeNull()
            progress?.deviceId.shouldBeNull()
            progress?.timestamp shouldBe 1_700_000_000L
        }
    }

    @Test
    fun `a push sends the xpointer as a string with a bare content type`() = runBlocking<Unit> {
        TinyHttpServer { TinyHttpServer.Response(200, """{"document":"abc","timestamp":1700000123}""") }.use { server ->
            val timestamp = client().putProgress(
                credentials(server.url),
                document = "abc",
                progress = "/body/DocFragment[1]/body/p[3]",
                percentage = 0.5,
                device = "Chimahon Pixel 8",
                deviceId = "DEV",
            )

            timestamp shouldBe 1_700_000_123L
            val request = server.requests.single()
            "${request.method} ${request.path}" shouldBe "PUT /syncs/progress"
            request.body.toString(Charsets.UTF_8) shouldBe
                """{"document":"abc","progress":"/body/DocFragment[1]/body/p[3]","percentage":0.5,"device":"Chimahon Pixel 8","device_id":"DEV"}"""
            // Bare, as KOReader sends it.
            request.header("Content-Type") shouldBe "application/json"
        }
    }

    @Test
    fun `server refusals are worded for the user`() {
        fun messageFor(status: Int): String? = TinyHttpServer { TinyHttpServer.Response(status) }.use { server ->
            assertThrows(KosyncServerException::class.java) {
                runBlocking { client().authorize(credentials(server.url)) }
            }.message
        }
        messageFor(401) shouldBe "Incorrect username or password."
        messageFor(402) shouldBe "Username is already taken."
        messageFor(403) shouldBe "Unknown user."
        messageFor(500) shouldBe "Server returned HTTP 500."
        assertThrows(KosyncServerException::class.java) {
            runBlocking { client().authorize(credentials("http://")) }
        }.message shouldBe "The server address is not a valid URL."
        // The register call sends the hashed key as the password, as the KOReader plugin does.
        TinyHttpServer { TinyHttpServer.Response(201, """{"username":"reader"}""") }.use { server ->
            runBlocking { client().register(credentials(server.url)) }
            server.requests.single().body.toString(Charsets.UTF_8) shouldContain
                """"password":"0123456789abcdef0123456789abcdef""""
        }
    }

    @Test
    fun `a redirect is refused and names the new address`() {
        TinyHttpServer { TinyHttpServer.Response(301, headers = mapOf("Location" to "https://host/")) }.use { server ->
            assertThrows(KosyncServerException::class.java) {
                runBlocking {
                    client().putProgress(
                        credentials(server.url),
                        document = "abc",
                        progress = "/body/DocFragment[1]/body",
                        percentage = 0.5,
                        device = "Chimahon Pixel 8",
                        deviceId = "DEV",
                    )
                }
            }.message shouldBe "The server redirected to https://host; use that address."
            // Not followed: the server saw the PUT and nothing after it.
            val request = server.requests.single()
            "${request.method} ${request.path}" shouldBe "PUT /syncs/progress"
        }
    }

    @Test
    fun `a sign-in through a redirect fails and names the new address`() {
        // A sign-in saves the login only once this check passes, so such an address is never saved.
        TinyHttpServer { TinyHttpServer.Response(301, headers = mapOf("Location" to "https://host/")) }.use { server ->
            assertThrows(KosyncServerException::class.java) {
                runBlocking { client().authorize(credentials(server.url)) }
            }.message shouldBe "The server redirected to https://host; use that address."
        }
    }

    @Test
    fun `a redirect that keeps the address means no sync service answers there`() {
        TinyHttpServer { request ->
            TinyHttpServer.Response(308, ByteArray(0), mapOf("Location" to "http://${request.header("Host")}${request.path}/"))
        }.use { server ->
            val message = assertThrows(KosyncServerException::class.java) {
                runBlocking { client().authorize(credentials(server.url)) }
            }.message
            // The Location repeats the request path; naming it would make the user paste a wrong address.
            message shouldBe "No KOReader sync service at ${server.url}: the server redirected /users/auth elsewhere. " +
                "Check the address (Calibre-Web Automated serves it under /kosync)."
        }
    }
}
