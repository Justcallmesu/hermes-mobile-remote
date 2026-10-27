package io.github.nideta231.hermesremote.data

import org.json.JSONObject

/** One folder level on the PC as the bridge's `/v1/folders` lists it (folders only, no files). */
data class FolderListing(val path: String, val parent: String?, val home: String, val git: Boolean,
                         val dirs: List<FolderEntry>, val truncated: Boolean = false)

data class FolderEntry(val name: String, val path: String, val git: Boolean)

fun parseFolders(o: JSONObject) = FolderListing(o.str("path").orEmpty(), o.str("parent")?.takeIf { it.isNotBlank() },
    o.str("home").orEmpty(), o.optBoolean("git"),
    o.optJSONArray("dirs").objects().map { FolderEntry(it.str("name").orEmpty(), it.str("path").orEmpty(), it.optBoolean("git")) },
    o.optBoolean("truncated"))

/** `~/x` for paths under [home], so the picker shows short paths; others stay absolute. */
fun shortPath(path: String, home: String): String = when {
    home.isBlank() -> path
    path == home -> "~"
    path.startsWith("$home/") -> "~" + path.removePrefix(home)
    else -> path
}
