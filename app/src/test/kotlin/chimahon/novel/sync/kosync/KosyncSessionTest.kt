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
    fun `each trigger needs Enable, a login, direction Auto, Auto Sync and its own switch`() {
        val session = session()
        fun triggers() = listOf(session.autoOpen, session.autoForeground, session.autoClose, session.autoPeriodic)

        preferences.autoSyncOnOpen().set(true)
        preferences.autoSyncOnClose().set(true)
        preferences.autoSyncPeriodic().set(true)
        preferences.autoSyncEnabled().set(true)
        triggers() shouldBe listOf(false, false, false, false)

        preferences.enabled().set(true)
        triggers() shouldBe listOf(false, false, false, false)

        preferences.saveLogin(credentials)
        session.isActive shouldBe true
        triggers() shouldBe listOf(true, true, true, true)

        preferences.direction().set(KosyncPreferences.DIRECTION_MANUAL)
        session.isManual shouldBe true
        triggers() shouldBe listOf(false, false, false, false)
        preferences.direction().set(KosyncPreferences.DIRECTION_AUTO)
        session.isManual shouldBe false

        preferences.autoSyncEnabled().set(false)
        triggers() shouldBe listOf(false, false, false, false)
        preferences.autoSyncEnabled().set(true)

        // The pull on coming back to a book needs no trigger of its own, like the TTU sync's.
        preferences.autoSyncOnOpen().set(false)
        preferences.autoSyncOnClose().set(false)
        preferences.autoSyncPeriodic().set(false)
        triggers() shouldBe listOf(false, true, false, false)
        preferences.autoSyncOnOpen().set(true)
        triggers() shouldBe listOf(true, true, false, false)
        preferences.autoSyncOnOpen().set(false)
        preferences.autoSyncOnClose().set(true)
        triggers() shouldBe listOf(false, true, true, false)
        preferences.autoSyncOnClose().set(false)
        preferences.autoSyncPeriodic().set(true)
        triggers() shouldBe listOf(false, true, false, true)

        preferences.enabled().set(false)
        session.isActive shouldBe false
        triggers() shouldBe listOf(false, false, false, false)
    }

    @Test
    fun `the periodic interval is at least a minute`() {
        val session = session()
        session.periodicIntervalMins shouldBe 10
        preferences.autoSyncIntervalMins().set(3)
        session.periodicIntervalMins shouldBe 3
        preferences.autoSyncIntervalMins().set(0)
        session.periodicIntervalMins shouldBe 1
        preferences.autoSyncIntervalMins().set(-5)
        session.periodicIntervalMins shouldBe 1
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
