package chimahon.novel.sync.kosync

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class KosyncPreferencesTest {
    private val store = FakePreferenceStore()
    private val preferences = KosyncPreferences(store)

    @Test
    fun `the login is the md5 of the password and stays out of backups`() {
        val credentials = KosyncPreferences.credentialsFor(" http://server:7200 ", " reader ", "hunter2")
        credentials shouldBe KosyncCredentials("http://server:7200", "reader", "2ab96390c7dbe3439de74d0c9b0b1767")
        // The key logs in like the password, so printing the login leaves it out.
        credentials.toString() shouldNotContain credentials.userKey

        preferences.credentials().shouldBeNull()
        preferences.saveLogin(credentials)
        preferences.credentials() shouldBe credentials
        val userKeyEntry = store.getAll().entries.single { it.value == credentials.userKey }
        Preference.isPrivate(userKeyEntry.key) shouldBe true

        preferences.clearLogin()
        preferences.credentials().shouldBeNull()
    }

    @Test
    fun `a Calibre-Web Automated login keeps the password itself under the private key`() {
        preferences.serverType().get() shouldBe KosyncPreferences.SERVER_TYPE_KOSYNC
        preferences.saveLogin(KosyncPreferences.credentialsFor("https://cwa/kosync", "sen", "x"))
        preferences.credentials()?.basicAuth shouldBe false
        preferences.clearLogin()

        preferences.serverType().set(KosyncPreferences.SERVER_TYPE_CWA)
        val c = KosyncPreferences.credentialsFor("https://cwa/kosync", "sen", "hunter2", basicAuth = true)
        c shouldBe KosyncCredentials("https://cwa/kosync", "sen", "hunter2", basicAuth = true)
        c.toString() shouldNotContain "hunter2"

        preferences.saveLogin(c)
        preferences.credentials() shouldBe c
        val userKeyEntry = store.getAll().entries.single { it.value == "hunter2" }
        Preference.isPrivate(userKeyEntry.key) shouldBe true
    }

    @Test
    fun `a login is incomplete until server, user and key are all set`() {
        preferences.serverUrl().set("http://server")
        preferences.username().set("reader")
        preferences.credentials().shouldBeNull()
        preferences.userKey().set("key")
        preferences.credentials() shouldBe KosyncCredentials("http://server", "reader", "key")
    }

    @Test
    fun `the device id is made once and is not a setting`() {
        val first = preferences.deviceId
        first.length shouldBe 32
        first.all { it in "0123456789ABCDEF" } shouldBe true
        KosyncPreferences(store).deviceId shouldBe first
        Preference.isAppState(store.getAll().keys.single()) shouldBe true
    }

    @Test
    fun `defaults mirror the TTU sync, off with direction Auto and a 10 minute interval`() {
        preferences.enabled().get() shouldBe false
        preferences.direction().get() shouldBe KosyncPreferences.DIRECTION_AUTO
        preferences.autoSyncEnabled shouldBe false
        preferences.autoSyncOnOpen().get() shouldBe false
        preferences.autoSyncOnClose().get() shouldBe false
        preferences.autoSyncPeriodic().get() shouldBe false
        preferences.autoSyncIntervalMins().get() shouldBe 10
        store.getAll().keys.none { it.endsWith("kosync_auto_sync") } shouldBe true
    }

    @Test
    fun `Auto Sync is on as soon as any trigger is`() {
        listOf(preferences.autoSyncOnOpen(), preferences.autoSyncOnClose(), preferences.autoSyncPeriodic())
            .forEach { trigger ->
                preferences.autoSyncEnabled shouldBe false
                trigger.set(true)
                preferences.autoSyncEnabled shouldBe true
                trigger.set(false)
            }
        preferences.autoSyncEnabled shouldBe false
    }
}
