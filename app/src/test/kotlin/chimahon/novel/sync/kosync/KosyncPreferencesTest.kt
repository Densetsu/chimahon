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
    fun `defaults are off, pull on, push on`() {
        preferences.enabled().get() shouldBe false
        preferences.autoSync().get() shouldBe true
        preferences.push().get() shouldBe true
    }
}
