package chimahon.novel.sync.kosync

import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The switches, the login, this device's identity and the two exchanges with the server. The
 * manager adds how documents are identified and how a position converts.
 */
class KosyncSession(
    private val preferences: KosyncPreferences,
    private val api: KosyncApi,
    private val deviceName: String,
    private val pullTimeout: Duration = 7.seconds,
) {
    /** Sync is switched on and there is a login to use. */
    val isActive: Boolean get() = preferences.enabled().get() && preferences.credentials() != null

    /** Whether opening or returning to a book asks the server for a newer position. */
    val canPull: Boolean get() = isActive && preferences.autoSync().get()

    /** Whether leaving a book sends its position. */
    val canPush: Boolean get() = isActive && preferences.push().get()

    /**
     * Checks [credentials] with the server, creating the account first when [register] is set,
     * and saves them once the server has accepted them. Throws with the server's answer otherwise.
     */
    suspend fun signIn(credentials: KosyncCredentials, register: Boolean) {
        if (register) api.register(credentials)
        api.authorize(credentials)
        preferences.saveLogin(credentials)
    }

    fun signOut() = preferences.clearLogin()

    /**
     * The server's position for [document] when it should replace the local one, otherwise null.
     *
     * It replaces the local one when another device put it there after [localSeconds], the unix
     * time the local position last changed. Pass null when there is no local position: then any
     * position from another device is taken. A book waits for this before it opens, so a server
     * that has not answered within [pullTimeout] counts as having nothing newer.
     */
    suspend fun fetchNewer(document: String, localSeconds: Long?): KosyncRemoteProgress? {
        val credentials = preferences.credentials() ?: return null
        val remote = withTimeoutOrNull(pullTimeout) { api.getProgress(credentials, document) } ?: return null
        return remote.takeIf { isNewer(it, preferences.deviceId, localSeconds) }
    }

    /** Sends a position and returns the server's timestamp for it, unix seconds, when it gives one. */
    suspend fun push(document: String, progress: String, percentage: Double): Long? {
        val credentials = preferences.credentials() ?: return null
        return api.putProgress(
            credentials = credentials,
            document = document,
            progress = progress,
            percentage = percentage,
            device = deviceName,
            deviceId = preferences.deviceId,
        )
    }

    companion object {
        internal fun isNewer(remote: KosyncRemoteProgress, ownDeviceId: String, localSeconds: Long?): Boolean {
            if (remote.percentage == null || remote.deviceId == ownDeviceId) return false
            if (localSeconds == null) return true
            return remote.timestamp != null && remote.timestamp > localSeconds
        }
    }
}
