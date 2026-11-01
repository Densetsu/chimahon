package chimahon.novel.sync.kosync

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The server answered, but not with what was asked for. [message] is written for the user. */
class KosyncServerException(message: String, val statusCode: Int? = null) : IOException(message)

/**
 * [KosyncApi] over OkHttp.
 *
 * Every request carries either the `x-auth-user` / `x-auth-key` pair, where the key is the MD5 of
 * the password rather than the password itself, exactly as the KOReader plugin sends it, or, for
 * Calibre-Web Automated ([KosyncCredentials.basicAuth]), HTTP Basic auth with the plain password.
 * Timestamps the server returns are unix seconds.
 */
class KosyncClient(
    client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    pullConnectTimeout: Duration = 3.seconds,
    pullReadTimeout: Duration = 4.seconds,
) : KosyncApi {
    // Neither client follows a redirect. OkHttp follows a 301 or 302 by turning a PUT or POST into
    // a GET, so pushes to an address that redirects would never be stored; refused, the redirect
    // fails the sign-in instead, and request() names the address to use.
    private val client = client.withoutRedirects().withTimeouts(connect = 10.seconds, read = 15.seconds)

    // A pull holds a book back from opening, so it gives up sooner than a push, and the call
    // timeout bounds it as a whole however many addresses the server name resolves to.
    private val pullClient = client.withoutRedirects().withTimeouts(
        connect = pullConnectTimeout,
        read = pullReadTimeout,
        call = pullConnectTimeout + pullReadTimeout,
    )

    override suspend fun register(credentials: KosyncCredentials) {
        val payload = buildJsonObject {
            put("username", credentials.username)
            put("password", credentials.userKey)
        }
        request(credentials, "POST", "users/create", payload)
    }

    override suspend fun authorize(credentials: KosyncCredentials) {
        request(credentials, "GET", "users/auth")
    }

    override suspend fun getProgress(credentials: KosyncCredentials, document: String): KosyncRemoteProgress? {
        val body = request(
            credentials,
            "GET",
            "syncs/progress/$document",
            notFoundIsNull = true,
            client = pullClient,
        ) ?: return null
        return KosyncRemoteProgress(
            progress = body.string("progress"),
            percentage = (body["percentage"] as? JsonPrimitive)?.doubleOrNull,
            device = body.string("device"),
            deviceId = body.string("device_id"),
            timestamp = body.timestamp(),
        )
    }

    override suspend fun putProgress(
        credentials: KosyncCredentials,
        document: String,
        progress: String,
        percentage: Double,
        device: String,
        deviceId: String,
    ): Long? {
        val payload = buildJsonObject {
            put("document", document)
            put("progress", progress)
            put("percentage", percentage)
            put("device", device)
            put("device_id", deviceId)
        }
        return request(credentials, "PUT", "syncs/progress", payload)?.timestamp()
    }

    private suspend fun request(
        credentials: KosyncCredentials,
        method: String,
        path: String,
        payload: JsonObject? = null,
        notFoundIsNull: Boolean = false,
        client: OkHttpClient = this.client,
    ): JsonObject? = withContext(ioDispatcher) {
        val url = credentials.serverUrl.withHttpScheme().toHttpUrlOrNull()
            ?.newBuilder()?.addPathSegments(path)?.build()
            ?: throw KosyncServerException("The server address is not a valid URL.")
        val request = Request.Builder()
            .url(url)
            // A byte body keeps the content type bare, as KOReader sends it; a string body
            // would have a charset appended.
            .method(method, payload?.toString()?.toByteArray(Charsets.UTF_8)?.toRequestBody(JSON))
            .headers(
                Headers.Builder()
                    .add("Accept", "application/vnd.koreader.v1+json")
                    .apply {
                        if (credentials.basicAuth) {
                            add(
                                "Authorization",
                                Credentials.basic(credentials.username, credentials.userKey, Charsets.UTF_8),
                            )
                        } else {
                            addUnsafeNonAscii("x-auth-user", credentials.username)
                            add("x-auth-key", credentials.userKey)
                        }
                    }
                    .build(),
            )
            .build()
        client.newCall(request).await().use { response ->
            val status = response.code
            val text = response.body.string()
            when {
                status in 300..399 ->
                    throw KosyncServerException(redirectMessage(url, path, response.header("Location")), status)
                response.isSuccessful ->
                    runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                        ?: if (text.isBlank()) {
                            JsonObject(emptyMap())
                        } else {
                            throw KosyncServerException("Unexpected response from server.", status)
                        }
                status == 404 && notFoundIsNull -> null
                status == 401 -> throw KosyncServerException("Incorrect username or password.", status)
                status == 402 -> throw KosyncServerException("Username is already taken.", status)
                status == 403 -> throw KosyncServerException("Unknown user.", status)
                else -> throw KosyncServerException("Server returned HTTP $status.", status)
            }
        }
    }

    /** The field as text; null when it is absent, blank or not a JSON primitive. */
    /**
     * Names the server the redirect points at, not the redirected request: a `Location` usually
     * repeats the request path, which pasted back into the address field would double it. A
     * redirect that keeps the address (a trailing slash, a login page) means no sync service
     * answers at this path.
     */
    private fun redirectMessage(requested: HttpUrl, path: String, location: String?): String {
        val typed = requested.toString().removeSuffix("/$path").trimEnd('/')
        val target = location?.trimEnd('/')?.removeSuffix("/$path")?.trimEnd('/')
        return if (target == null || target == typed) {
            "No KOReader sync service at $typed: the server redirected /$path elsewhere. " +
                "Check the address (Calibre-Web Automated serves it under /kosync)."
        } else {
            "The server redirected to $target; use that address."
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** The field as unix seconds; a fractional value, which some servers send, is rounded down. */
    private fun JsonObject.timestamp(): Long? =
        (this["timestamp"] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    companion object {
        /**
         * The app's client without request logging, the Cloudflare challenge interceptor or the HTTP
         * cache: the auth headers must not reach the log, a 403 from the sync server means an unknown
         * user, and a pull must never be served from cache.
         */
        fun httpClient(network: NetworkHelper): OkHttpClient = network.client.newBuilder().apply {
            interceptors().removeAll { it is HttpLoggingInterceptor }
            networkInterceptors().removeAll { it is HttpLoggingInterceptor }
            interceptors().removeAll { it is CloudflareInterceptor }
            networkInterceptors().removeAll { it is CloudflareInterceptor }
            cache(null)
        }.build()

        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** A client with these timeouts that shares this one's connections. [call] bounds a whole request. */
private fun OkHttpClient.withTimeouts(
    connect: Duration,
    read: Duration,
    write: Duration = read,
    call: Duration? = null,
): OkHttpClient = newBuilder()
    .connectTimeout(connect.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    .readTimeout(read.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    .writeTimeout(write.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    .apply { if (call != null) callTimeout(call.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
    .build()

/** A client that hands every redirect back to the caller instead of following it. */
private fun OkHttpClient.withoutRedirects(): OkHttpClient =
    newBuilder().followRedirects(false).followSslRedirects(false).build()

/** A typed address as a URL: `host:7200` becomes `http://host:7200`. */
internal fun String.withHttpScheme(): String = trim().let { if (it.contains("://")) it else "http://$it" }

/**
 * One line for the UI that names the cause: what the server said, or why it could not be
 * reached. The app's `await()` wraps network failures in a plain IOException, so causes are
 * checked too.
 */
fun Throwable.kosyncUserMessage(): String {
    val chain = generateSequence(this) { it.cause }.take(5).toList()
    chain.firstOrNull { it is KosyncServerException }?.message?.let { return it }
    for (error in chain) {
        when (error) {
            is UnknownHostException -> return "Server not found. Check the address and that this device can reach it."
            is ConnectException -> return "Could not connect to the server."
            is InterruptedIOException -> return "The server did not respond in time."
        }
    }
    return chain.firstNotNullOfOrNull { error -> error.message?.takeIf { it.isNotBlank() } }
        ?: this::class.java.simpleName
}
