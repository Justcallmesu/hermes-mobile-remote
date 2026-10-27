package io.github.nideta231.hermesremote.data

import org.json.JSONObject

/** One slice of the context window (system prompt, tools, memory, conversation...). */
data class ContextSlice(val id: String, val label: String, val tokens: Int)

/** Hermes' session.context_breakdown: what fills the open chat's context window. */
data class ContextInfo(val used: Int, val max: Int, val percent: Int, val model: String, val slices: List<ContextSlice>)

fun parseContext(o: JSONObject): ContextInfo {
    val arr = o.optJSONArray("categories")
    val slices = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        val c = arr!!.optJSONObject(i) ?: return@mapNotNull null
        ContextSlice(c.optString("id"), c.optString("label").ifBlank { c.optString("id") }, c.optInt("tokens"))
    }.filter { it.tokens > 0 }
    return ContextInfo(o.optInt("context_used"), o.optInt("context_max"), o.optInt("context_percent"),
        o.optString("model"), slices)
}
