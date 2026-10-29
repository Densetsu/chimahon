package chimahon.novel.sync.kosync

/** A kosync server holding one remote position, recording what it is asked. */
internal class FakeKosyncApi : KosyncApi {
    data class Put(
        val document: String,
        val progress: String,
        val percentage: Double,
        val device: String,
        val deviceId: String,
    )

    var remote: KosyncRemoteProgress? = null
    var putTimestamp: Long? = 1_700_000_500L
    val pulls = mutableListOf<String>()
    val puts = mutableListOf<Put>()
    val registered = mutableListOf<KosyncCredentials>()

    override suspend fun register(credentials: KosyncCredentials) {
        registered += credentials
    }

    override suspend fun authorize(credentials: KosyncCredentials) = Unit

    override suspend fun getProgress(credentials: KosyncCredentials, document: String): KosyncRemoteProgress? {
        pulls += document
        return remote
    }

    override suspend fun putProgress(
        credentials: KosyncCredentials,
        document: String,
        progress: String,
        percentage: Double,
        device: String,
        deviceId: String,
    ): Long? {
        puts += Put(document, progress, percentage, device, deviceId)
        return putTimestamp
    }
}

internal fun remoteProgress(
    progress: String? = null,
    percentage: Double? = null,
    deviceId: String? = "KOBO",
    timestamp: Long? = null,
) = KosyncRemoteProgress(progress, percentage, device = "Kobo", deviceId = deviceId, timestamp = timestamp)
