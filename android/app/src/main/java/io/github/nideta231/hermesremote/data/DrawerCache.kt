package io.github.nideta231.hermesremote.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The drawer as last seen, per paired PC and Hermes profile: chats, projects and the custom group
 * order. Shown at once on start and kept when a sync fails, so the drawer never comes up empty or
 * with every chat lumped into Home while the PC is slow or away. Titles and stats only, no messages.
 */
class DrawerCache(private val prefs: SharedPreferences) {

    constructor(context: Context, pcId: String) :
        this(context.getSharedPreferences("drawer_cache_$pcId", Context.MODE_PRIVATE))

    fun sessions(profile: String): List<SessionSummary> = read(profile, "sessions") { arr ->
        arr.objects().mapNotNull { runCatching { sessionFrom(it) }.getOrNull() }
    }

    fun projects(profile: String): List<ProjectRef> = read(profile, "projects") { arr ->
        arr.objects().mapNotNull { runCatching { projectFrom(it) }.getOrNull() }
    }

    /** Custom group order (group keys, top first) for one grouping. */
    fun groupOrder(profile: String, grouping: SessionGrouping): List<String> =
        read(profile, "order_${grouping.name}") { it.strings() }

    fun putSessions(profile: String, items: List<SessionSummary>) =
        write(profile, "sessions", JSONArray().apply { items.forEach { put(sessionTo(it)) } })

    fun putProjects(profile: String, items: List<ProjectRef>) =
        write(profile, "projects", JSONArray().apply { items.forEach { put(projectTo(it)) } })

    fun putGroupOrder(profile: String, grouping: SessionGrouping, keys: List<String>) =
        write(profile, "order_${grouping.name}", JSONArray(keys))

    fun clear() = prefs.edit().clear().apply()

    private fun <T> read(profile: String, name: String, parse: (JSONArray) -> List<T>): List<T> =
        prefs.getString("$profile/$name", null)?.let { raw -> runCatching { parse(JSONArray(raw)) }.getOrNull() }.orEmpty()

    private fun write(profile: String, name: String, value: JSONArray) =
        prefs.edit().putString("$profile/$name", value.toString()).apply()

    companion object {
        fun sessionTo(s: SessionSummary): JSONObject = JSONObject()
            .put("id", s.id).put("title", s.title).put("preview", s.preview).put("source", s.source)
            .put("message_count", s.messageCount).put("last_active", s.lastActive).put("model", s.model)
            .put("pinned", s.pinned).put("started_at", s.startedAt).put("tokens", s.tokens)
            .put("cost", s.costUsd).put("unread", s.unread)

        fun sessionFrom(o: JSONObject) = SessionSummary(
            id = o.getString("id"), title = o.str("title"), preview = o.str("preview"), source = o.str("source"),
            messageCount = o.optInt("message_count"), lastActive = o.dbl("last_active"), model = o.str("model"),
            pinned = o.optBoolean("pinned"), startedAt = o.dbl("started_at"), tokens = o.optLong("tokens"),
            costUsd = o.dbl("cost") ?: 0.0, unread = o.optBoolean("unread"),
        )

        fun projectTo(p: ProjectRef): JSONObject = JSONObject()
            .put("id", p.id).put("label", p.label).put("sessions", JSONArray(p.sessionIds.toList()))
            .put("path", p.path).put("auto", p.auto)

        fun projectFrom(o: JSONObject) = ProjectRef(
            id = o.getString("id"), label = o.str("label") ?: o.getString("id"),
            sessionIds = o.optJSONArray("sessions").strings().filter { it.isNotBlank() }.toSet(),
            path = o.str("path"), auto = o.optBoolean("auto"),
        )
    }
}
