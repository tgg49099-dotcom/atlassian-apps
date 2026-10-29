package com.tgglabs.commithistorysearch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySearchSettingsTest {

    @Test
    fun `load and save settings correctly store and retrieve values`() {
        val store = mutableMapOf<String, Any>()

        fun getBoolean(key: String, def: Boolean): Boolean = (store[key] as? Boolean) ?: def
        fun getValue(key: String, def: String): String = (store[key] as? String) ?: def
        fun setBoolean(key: String, value: Boolean) { store[key] = value }
        fun setValue(key: String, value: String) { store[key] = value }

        val initialState = HistorySearchSettings.load(::getBoolean, ::getValue)
        assertFalse(initialState.caseSensitive)
        assertFalse(initialState.regexMode)
        assertEquals("", initialState.query)

        val newState = HistorySearchState(caseSensitive = true, regexMode = true, query = "testQuery")
        HistorySearchSettings.save(::setBoolean, ::setValue, newState)

        val reloadedState = HistorySearchSettings.load(::getBoolean, ::getValue)
        assertTrue(reloadedState.caseSensitive)
        assertTrue(reloadedState.regexMode)
        assertEquals("testQuery", reloadedState.query)
    }
}
