package com.tiagodias.igpsportkaroo

import android.content.SharedPreferences
import com.tiagodias.igpsportkaroo.light.SleepPause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SleepPauseStoreTest {
    /** Just enough of SharedPreferences, in memory. */
    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as Set<String>? ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { puts[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes += key }
            override fun clear() = apply { removes += values.keys }
            override fun commit(): Boolean {
                removes.forEach(values::remove)
                // Like the real thing: putting null removes the key.
                puts.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    private val a = "AA:AA:AA:AA:AA:AA"
    private val b = "BB:BB:BB:BB:BB:BB"

    @Test
    fun `each light keeps its own pause`() {
        val store = SleepPauseStore(FakePrefs())
        store.save(a, SleepPause.PAUSED)
        store.save(b, SleepPause.PAUSED) // must not erase A's record
        assertEquals(SleepPause.PAUSED, store.load(a))
        assertEquals(SleepPause.PAUSED, store.load(b))
        store.save(b, SleepPause.NONE)
        assertEquals(SleepPause.PAUSED, store.load(a))
        assertEquals(SleepPause.NONE, store.load(b))
    }

    @Test
    fun `a pending resume is kept too`() {
        val store = SleepPauseStore(FakePrefs())
        store.save(a, SleepPause.RESUMING)
        assertEquals(SleepPause.RESUMING, store.load(a))
    }

    @Test
    fun `nothing saved means not paused`() {
        assertEquals(SleepPause.NONE, SleepPauseStore(FakePrefs()).load(a))
    }

    @Test
    fun `clearing a pause removes its key`() {
        val prefs = FakePrefs()
        val store = SleepPauseStore(prefs)
        store.save(a, SleepPause.PAUSED)
        store.save(a, SleepPause.NONE)
        assertEquals(emptyMap<String, Any?>(), prefs.values)
    }

    @Test
    fun `the single-light record of the first version is migrated`() {
        val prefs = FakePrefs()
        prefs.values["sleep_paused_for"] = a
        val store = SleepPauseStore(prefs)
        assertEquals(SleepPause.NONE, store.load(b))
        store.save(b, SleepPause.PAUSED)
        assertEquals(SleepPause.PAUSED, store.load(a))
        assertEquals(SleepPause.PAUSED, store.load(b))
        assertFalse(prefs.contains("sleep_paused_for"))
    }

    @Test
    fun `migration keeps a newer per-light record`() {
        val prefs = FakePrefs()
        prefs.values["sleep_paused_for"] = a
        val store = SleepPauseStore(prefs)
        store.save(a, SleepPause.RESUMING) // written first, migrated after: the per-light record wins
        assertEquals(SleepPause.RESUMING, store.load(a))
        assertFalse(prefs.contains("sleep_paused_for"))
    }
}
