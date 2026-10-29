package chimahon.novel.sync.kosync

/** The KOReader progress-sync protocol (`plugins/kosync.koplugin/api.json`): four calls, no session. */
interface KosyncApi {
    /** `POST /users/create`. Throws [KosyncServerException] when the server refuses (402 = name taken). */
    suspend fun register(credentials: KosyncCredentials)

    /** `GET /users/auth`. Throws [KosyncServerException] when the login is refused. */
    suspend fun authorize(credentials: KosyncCredentials)

    /** `GET /syncs/progress/{document}`: the record, or null when the server has none. */
    suspend fun getProgress(credentials: KosyncCredentials, document: String): KosyncRemoteProgress?

    /** `PUT /syncs/progress`. Returns the server-assigned timestamp (unix seconds) when it reports one. */
    suspend fun putProgress(
        credentials: KosyncCredentials,
        document: String,
        progress: String,
        percentage: Double,
        device: String,
        deviceId: String,
    ): Long?
}
