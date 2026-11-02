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

/** What a library sync action did, for its one-line toast ([summary]). */
sealed interface KosyncSyncOutcome {
    val summary: String

    /** The book moved to the position [device] (its name as the server gave it) last pushed. */
    data class Pulled(val device: String?) : KosyncSyncOutcome {
        override val summary get() = "Pulled position from ${device?.takeIf { it.isNotBlank() } ?: "another device"}"
    }

    data object Pushed : KosyncSyncOutcome {
        override val summary get() = "Position pushed"
    }

    data object UpToDate : KosyncSyncOutcome {
        override val summary get() = "Already up to date"
    }

    /** An import found no position on the server. */
    data object NoRemotePosition : KosyncSyncOutcome {
        override val summary get() = "No position on the server for this book"
    }

    /** An export found no position here: the book was never opened. */
    data object NoLocalPosition : KosyncSyncOutcome {
        override val summary get() = "No reading position to push yet"
    }

    data object NoSourceEpub : KosyncSyncOutcome {
        override val summary get() = "No EPUB file kept for this book"
    }

    data class Failed(val message: String) : KosyncSyncOutcome {
        override val summary get() = message
    }
}
