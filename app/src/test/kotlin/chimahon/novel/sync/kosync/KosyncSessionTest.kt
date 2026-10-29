package chimahon.novel.sync.kosync

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KosyncSessionTest {
    private val preferences = KosyncPreferences(FakePreferenceStore())
    private val credentials = KosyncCredentials("http://server", "reader", "key")

    private fun session(api: KosyncApi = FakeKosyncApi()) =
        KosyncSession(preferences, api, deviceName = "Chimahon Test")

    @Test
    fun `a login is saved only once the server has accepted it`() {
        val refusing = object : KosyncApi by FakeKosyncApi() {
            override suspend fun authorize(credentials: KosyncCredentials) =
                throw KosyncServerException("Incorrect username or password.", 401)
        }
        assertThrows(KosyncServerException::class.java) {
            runBlocking { session(refusing).signIn(credentials, register = false) }
        }
        preferences.credentials().shouldBeNull()

        val api = FakeKosyncApi()
        val session = session(api)
        runBlocking { session.signIn(credentials, register = true) }
        api.registered shouldBe listOf(credentials)
        preferences.credentials() shouldBe credentials

        session.signOut()
        preferences.credentials().shouldBeNull()
    }

    @Test
    fun `the switches gate pulling and pushing, and neither works without a login`() {
        val session = session()
        preferences.enabled().set(true)
        (session.canPull || session.canPush) shouldBe false

        preferences.saveLogin(credentials)
        (session.canPull && session.canPush) shouldBe true

        preferences.autoSync().set(false)
        preferences.push().set(false)
        (session.canPull || session.canPush) shouldBe false
        session.isActive shouldBe true

        preferences.enabled().set(false)
        session.isActive shouldBe false
    }

    @Test
    fun `a remote position counts as newer only from another device and after the local change`() {
        val remote = remoteProgress(percentage = 0.5, deviceId = "KOBO", timestamp = 2_000L)
        KosyncSession.isNewer(remote, "PHONE", localSeconds = 1_999L) shouldBe true
        KosyncSession.isNewer(remote, "PHONE", localSeconds = 2_000L) shouldBe false
        KosyncSession.isNewer(remote, "KOBO", localSeconds = 1_999L) shouldBe false
        KosyncSession.isNewer(remote.copy(percentage = null), "PHONE", localSeconds = 1_999L) shouldBe false
        KosyncSession.isNewer(remote.copy(timestamp = null), "PHONE", localSeconds = 1_999L) shouldBe false
        // With no local position at all there is nothing to lose.
        KosyncSession.isNewer(remote.copy(timestamp = null), "PHONE", localSeconds = null) shouldBe true
    }

    @Test
    fun `a push carries this device's name and id`() = runBlocking<Unit> {
        preferences.enabled().set(true)
        preferences.saveLogin(credentials)
        val api = FakeKosyncApi()
        session(api).push("doc", "/body/DocFragment[1]/body", 0.25) shouldBe 1_700_000_500L
        api.puts.single() shouldBe
            FakeKosyncApi.Put("doc", "/body/DocFragment[1]/body", 0.25, "Chimahon Test", preferences.deviceId)
    }

    @Test
    fun `a server that takes too long counts as having nothing newer`() = runTest {
        preferences.saveLogin(credentials)
        val slow = object : KosyncApi by FakeKosyncApi() {
            override suspend fun getProgress(credentials: KosyncCredentials, document: String): KosyncRemoteProgress? {
                delay(60_000)
                return remoteProgress(percentage = 0.5, timestamp = 2_000L)
            }
        }
        session(slow).fetchNewer("document", localSeconds = 0L).shouldBeNull()
    }
}
