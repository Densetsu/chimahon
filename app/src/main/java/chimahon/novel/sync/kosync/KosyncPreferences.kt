package chimahon.novel.sync.kosync

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.security.MessageDigest
import java.util.UUID

/**
 * The kosync switches, the server login and this install's device id, in the app's preference
 * store.
 *
 * The password is never stored. KOReader authenticates with the MD5 of the password, and that
 * hash is what both the server and [userKey] hold. It sits under a private key, so a backup
 * leaves it out unless the user includes private settings. The device id is app state and never
 * goes into a backup, so restoring one on a second device does not make two devices look like
 * one.
 */
class KosyncPreferences(private val store: PreferenceStore) {

    fun enabled() = store.getBoolean("kosync_enabled", false)

    /** Pull a newer position when a book opens or comes back to the front. */
    fun autoSync() = store.getBoolean("kosync_auto_sync", true)

    /** Send this device's position when a book is left. */
    fun push() = store.getBoolean("kosync_push", true)

    fun serverUrl() = store.getString("kosync_server_url", "")

    fun username() = store.getString("kosync_username", "")

    fun userKey() = store.getString(Preference.privateKey("kosync_user_key"), "")

    private fun storedDeviceId() = store.getString(Preference.appStateKey("kosync_device_id"), "")

    /** The saved login, or null until server, username and key have all been given. */
    fun credentials(): KosyncCredentials? =
        KosyncCredentials(serverUrl().get(), username().get(), userKey().get())
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
        /** A login as typed. [password] is hashed here and goes no further. */
        fun credentialsFor(serverUrl: String, username: String, password: String) = KosyncCredentials(
            serverUrl = serverUrl.trim(),
            username = username.trim(),
            userKey = MessageDigest.getInstance("MD5")
                .digest(password.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
    }
}
