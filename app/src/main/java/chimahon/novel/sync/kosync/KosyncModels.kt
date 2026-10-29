package chimahon.novel.sync.kosync

import kotlinx.serialization.Serializable

/** A saved login: the server address and the `x-auth-user` / `x-auth-key` pair (the key is MD5 of the password). */
data class KosyncCredentials(
    val serverUrl: String,
    val username: String,
    val userKey: String,
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
