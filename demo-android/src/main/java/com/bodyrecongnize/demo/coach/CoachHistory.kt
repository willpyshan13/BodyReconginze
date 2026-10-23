package com.bodyrecongnize.demo.coach

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class CoachHistory(context: Context) {
    private val preferences = context.getSharedPreferences("coach_history", Context.MODE_PRIVATE)
    fun entries(): List<JSONObject> = runCatching {
        val array = JSONArray(preferences.getString("sessions", "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }
    }.getOrDefault(emptyList())
    fun save(entry: JSONObject) {
        val entries = listOf(entry) + entries().filter { it.optString("id") != entry.getString("id") }
        preferences.edit().putString("sessions", JSONArray(entries.take(20)).toString()).apply()
    }
}
