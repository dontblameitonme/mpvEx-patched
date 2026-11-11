package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.Preference
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Pure JVM in-memory [PreferenceStore] for unit testing preference isolation across
 * [SubtitleMode.Single] and [SubtitleMode.Multi] without Android SharedPreferences.
 */
class InMemoryPreferenceStore : PreferenceStore {
  private val backing = linkedMapOf<String, Any?>()
  private val flows = linkedMapOf<String, MutableStateFlow<Any?>>()

  override fun getString(key: String, defaultValue: String): Preference<String> =
    InMemoryPreference(key, defaultValue, { it as? String ?: defaultValue }, { it })

  override fun getLong(key: String, defaultValue: Long): Preference<Long> =
    InMemoryPreference(key, defaultValue, { (it as? Number)?.toLong() ?: defaultValue }, { it })

  override fun getInt(key: String, defaultValue: Int): Preference<Int> =
    InMemoryPreference(key, defaultValue, { (it as? Number)?.toInt() ?: defaultValue }, { it })

  override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
    InMemoryPreference(key, defaultValue, { (it as? Number)?.toFloat() ?: defaultValue }, { it })

  override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
    InMemoryPreference(key, defaultValue, { it as? Boolean ?: defaultValue }, { it })

  @Suppress("UNCHECKED_CAST")
  override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
    InMemoryPreference(key, defaultValue, { (it as? Set<String>) ?: defaultValue }, { it })

  override fun <T> getObject(
    key: String,
    defaultValue: T,
    serializer: (T) -> String,
    deserializer: (String) -> T,
  ): Preference<T> =
    InMemoryPreference(
      keyName = key,
      defVal = defaultValue,
      decode = { raw -> (raw as? String)?.let(deserializer) ?: defaultValue },
      encode = serializer,
    )

  override fun getAll(): Map<String, *> = backing.toMap()

  private inner class InMemoryPreference<T>(
    private val keyName: String,
    private val defVal: T,
    private val decode: (Any?) -> T,
    private val encode: (T) -> Any?,
  ) : Preference<T> {

    private fun ensureFlow(): MutableStateFlow<T> {
      @Suppress("UNCHECKED_CAST")
      return flows.getOrPut(keyName) { MutableStateFlow(get() as Any?) } as MutableStateFlow<T>
    }

    override fun key(): String = keyName

    override fun get(): T {
      val raw = backing[keyName] ?: return defVal
      return decode(raw)
    }

    override fun set(value: T) {
      backing[keyName] = encode(value)
      ensureFlow().value = value
    }

    override fun isSet(): Boolean = backing.containsKey(keyName)

    override fun delete() {
      backing.remove(keyName)
      ensureFlow().value = defVal
    }

    override fun defaultValue(): T = defVal

    override fun changes(): Flow<T> = ensureFlow().asStateFlow()

    override fun stateIn(scope: CoroutineScope): StateFlow<T> =
      MutableStateFlow(get()).asStateFlow()
  }
}
