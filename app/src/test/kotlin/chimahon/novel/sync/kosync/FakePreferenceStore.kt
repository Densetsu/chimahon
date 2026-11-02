package chimahon.novel.sync.kosync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * A preference store for tests that remembers what is set. Upstream's `InMemoryPreferenceStore`
 * hands out a fresh preference on every call and so forgets every write. Objects are kept in
 * their serialized form, so a test also covers the round trip.
 */
class FakePreferenceStore : PreferenceStore {
    private val values = MutableStateFlow<Map<String, Any>>(emptyMap())

    private inner class Stored<T>(
        private val key: String,
        private val default: T,
        private val encode: (T) -> Any,
        private val decode: (Any) -> T,
    ) : Preference<T> {
        override fun key() = key
        override fun get(): T = values.value[key]?.let(decode) ?: default
        override fun set(value: T) {
            values.value = values.value + (key to encode(value))
        }
        override fun isSet() = key in values.value
        override fun delete() {
            values.value = values.value - key
        }
        override fun defaultValue() = default
        override fun changes(): Flow<T> = values.map { get() }
        override fun stateIn(scope: CoroutineScope): StateFlow<T> =
            changes().stateIn(scope, SharingStarted.Eagerly, get())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> plain(key: String, default: T): Preference<T> = Stored(key, default, { it }, { it as T })

    override fun getString(key: String, defaultValue: String) = plain(key, defaultValue)
    override fun getLong(key: String, defaultValue: Long) = plain(key, defaultValue)
    override fun getInt(key: String, defaultValue: Int) = plain(key, defaultValue)
    override fun getFloat(key: String, defaultValue: Float) = plain(key, defaultValue)
    override fun getBoolean(key: String, defaultValue: Boolean) = plain(key, defaultValue)
    override fun getStringSet(key: String, defaultValue: Set<String>) = plain(key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = Stored(key, defaultValue, { serializer(it) }, { deserializer(it as String) })

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = Stored(key, defaultValue, { serializer(it) }, { deserializer(it as Int) })

    override fun getAll(): Map<String, *> = values.value
}
