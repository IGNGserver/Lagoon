package com.igng.opencode.lagoon.core

import android.content.SharedPreferences

/** Minimal in-memory SharedPreferences for pure JVM tests of the local stores. */
internal class FakeSharedPreferences : SharedPreferences {
  val values = LinkedHashMap<String, Any?>()

  override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
  override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
  @Suppress("UNCHECKED_CAST")
  override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
    (values[key] as? Set<String>)?.toMutableSet() ?: defValues
  override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
  override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
  override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
  override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
  override fun contains(key: String): Boolean = values.containsKey(key)
  override fun edit(): SharedPreferences.Editor = Editor()
  override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
  override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

  inner class Editor : SharedPreferences.Editor {
    private val pending = LinkedHashMap<String, Any?>()
    private val removed = HashSet<String>()
    private var clear = false

    override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key] = values?.toSet() }
    override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
    override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
    override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
    override fun remove(key: String): SharedPreferences.Editor = apply { removed += key }
    override fun clear(): SharedPreferences.Editor = apply { clear = true }
    override fun commit(): Boolean {
      if (clear) values.clear()
      removed.forEach(values::remove)
      pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
      pending.clear(); removed.clear(); clear = false
      return true
    }
    override fun apply() { commit() }
  }
}
