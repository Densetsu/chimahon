package chimahon.novel.sync.kosync

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.security.MessageDigest
import java.util.UUID

/**
 * The kosync switches, the server login and this install's device id, in the app's preference
 * store.
 *
 * The password is never stored as typed, except for Calibre-Web Automated. KOReader authenticates
 * with the MD5 of the password, and that hash is what both the server and [userKey] hold. CWA
 * checks the password, not its hash, so for CWA the password itself is stored. Either way it sits
 * under a private key, so a backup leaves it out unless the user includes private settings. The
 * device id is app state and never goes into a backup, so restoring one on a second device does
 * not make two devices look like one.
 */
class KosyncPreferences(private val store: PreferenceStore) {

    fun enabled() = store.getBoolean("kosync_enabled", false)

    /**
     * [DIRECTION_AUTO]: the library action resolves by the newest timestamp. [DIRECTION_MANUAL]: the
     * library action is a menu of Auto, Import and Export. The reader's triggers do not depend on it.
     */
    fun direction() = store.getString("kosync_direction", DIRECTION_AUTO)

    /** Any trigger is on, as the TTU sync defines Auto Sync; it also gates the pull on resume. */
    val autoSyncEnabled: Boolean
        get() = autoSyncOnOpen().get() || autoSyncOnClose().get() || autoSyncPeriodic().get()

    /** Pull a newer position when a book opens. */
    fun autoSyncOnOpen() = store.getBoolean("kosync_auto_sync_open", false)

    /** Send this device's position when a book is left. */
    fun autoSyncOnClose() = store.getBoolean("kosync_auto_sync_close", false)

    /** Send this device's position every [autoSyncIntervalMins] minutes while reading. */
    fun autoSyncPeriodic() = store.getBoolean("kosync_auto_sync_periodic", false)

    fun autoSyncIntervalMins() = store.getInt("kosync_auto_sync_interval_mins", 10)

    /** [SERVER_TYPE_KOSYNC] or [SERVER_TYPE_CWA]. */
    fun serverType() = store.getString("kosync_server_type", SERVER_TYPE_KOSYNC)

    fun serverUrl() = store.getString("kosync_server_url", "")

    fun username() = store.getString("kosync_username", "")

    fun userKey() = store.getString(Preference.privateKey("kosync_user_key"), "")

    private fun storedDeviceId() = store.getString(Preference.appStateKey("kosync_device_id"), "")

    /** The saved login, or null until server, username and key have all been given. */
    fun credentials(): KosyncCredentials? =
        KosyncCredentials(
            serverUrl().get(),
            username().get(),
            userKey().get(),
            basicAuth = serverType().get() == SERVER_TYPE_CWA,
        )
            .takeIf { it.serverUrl.isNotBlank() && it.username.isNotBlank() && it.userKey.isNotBlank() }

    fun saveLogin(credentials: KosyncCredentials) {
        serverUrl().set(credentials.serverUrl)
        username().set(credentials.username)
        userKey().set(credentials.userKey)
    }

    fun clearLogin() {
        serverUrl().delete()
        username().delete()
        userKey().delete()
    }

    /** Minted on first use, under a lock so that two first callers cannot mint two ids. */
    val deviceId: String
        @Synchronized get() = storedDeviceId().get().ifBlank {
            UUID.randomUUID().toString().replace("-", "").uppercase().also(storedDeviceId()::set)
        }

    companion object {
        const val SERVER_TYPE_KOSYNC = "kosync"
        const val SERVER_TYPE_CWA = "cwa"

        const val DIRECTION_AUTO = "auto"
        const val DIRECTION_MANUAL = "manual"

        /**
         * A login as typed. [password] is hashed here and goes no further, unless [basicAuth]
         * (Calibre-Web Automated), which needs the password itself.
         */
        fun credentialsFor(
            serverUrl: String,
            username: String,
            password: String,
            basicAuth: Boolean = false,
        ) = KosyncCredentials(
            serverUrl = serverUrl.trim(),
            username = username.trim(),
            userKey = if (basicAuth) {
                password
            } else {
                MessageDigest.getInstance("MD5")
                    .digest(password.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            },
            basicAuth = basicAuth,
        )
    }
}
