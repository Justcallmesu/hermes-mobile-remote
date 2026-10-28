package io.github.nideta231.hermesremote.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** The watched sessions on disk, so a restarted process can resume them. A corrupt file reads as empty. */
class WatchedStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("watched_sessions", Context.MODE_PRIVATE))

    fun save(watched: Map<String, LiveLink.Watched>) {
        val arr = JSONArray()
        watched.forEach { (rid, w) ->
            arr.put(JSONObject().put("runtime", rid).put("stored", w.storedId).put("title", w.title ?: JSONObject.NULL).put("profile", w.profile ?: JSONObject.NULL))
        }
        prefs.edit().putString("watched", arr.toString()).apply()
    }

    fun load(): Map<String, LiveLink.Watched> = try {
        val arr = JSONArray(prefs.getString("watched", null) ?: "[]")
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                put(o.getString("runtime"), LiveLink.Watched(o.getString("stored"),
                    o.optString("title").takeIf { !o.isNull("title") && it.isNotEmpty() },
                    o.optString("profile").takeIf { !o.isNull("profile") && it.isNotEmpty() }))
            }
        }
    } catch (e: org.json.JSONException) {
        emptyMap()
    }

    /** Process start: everything saved is watched again and resumed on the first Open. */
    fun restoreInto(watched: MutableMap<String, LiveLink.Watched>, toResume: MutableSet<String>) {
        val saved = load()
        watched.putAll(saved)
        toResume += saved.keys
    }
}
