package chimahon.novel.sync.kosync

import kotlinx.serialization.Serializable

/**
 * A saved login: the server address, the username and [userKey].
 *
 * userKey is the MD5 of the password for a standard kosync server, or the password itself when
 * basicAuth is set (Calibre-Web Automated).
 */
data class KosyncCredentials(
    val serverUrl: String,
    val username: String,
    val userKey: String,
    val basicAuth: Boolean = false,
) {
    /** The key logs in like the password, so it stays out of logs and crash reports. */
    override fun toString() = "KosyncCredentials(serverUrl=$serverUrl, username=$username, userKey=****)"
}

/** One record from `GET /syncs/progress/{document}`. Every field is optional on the wire. */
data class KosyncRemoteProgress(
    val progress: String?,
    val percentage: Double?,
    val device: String?,
    val deviceId: String?,
    /** Server-assigned, unix seconds. */
    val timestamp: Long?,
)

/** Per-book sync bookkeeping, stored as `kosync.json` beside `bookmark.json`. */
@Serializable
data class KosyncBookState(
    val lastSyncedCharacterCount: Int? = null,
    val lastServerTimestamp: Long? = null,
)
