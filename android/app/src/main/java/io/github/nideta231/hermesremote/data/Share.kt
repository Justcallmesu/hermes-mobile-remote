package io.github.nideta231.hermesremote.data

/**
 * What another app shared to Hermes (Android's share sheet), ready for the composer: [text] goes
 * into the draft, [images] and [files] are `content://` uris. [dropped] counts what was left out.
 * Plain Kotlin so it is unit-tested; reading the Intent and copying the bytes is [SharedFiles].
 */
data class SharedContent(
    val text: String = "",
    val images: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val dropped: Int = 0,
) {
    val isEmpty: Boolean get() = text.isEmpty() && images.isEmpty() && files.isEmpty()
    val uris: List<String> get() = images + files

    /** "a link · 2 pictures · 1 file", for the share sheet. */
    fun summary(): String = listOfNotNull(
        text.takeIf { it.isNotEmpty() }?.let { if (isLink(it)) "a link" else "text" },
        count(images.size, "picture"),
        count(files.size, "file"),
    ).joinToString(" · ")
}

/** One shared stream: its uri and the type its app reported (null when unknown). */
data class SharedStream(val uri: String, val mime: String?)

object Share {
    const val SEND = "android.intent.action.SEND"
    const val SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    /** Under the app's cache; copies of shared streams live in one folder each. */
    const val CACHE_DIR = "shared"

    /**
     * Sort a share into draft text, pictures and files. Null when [action] isn't a share. Only
     * `content://` streams of other apps are taken: a `file://` path or our own provider would
     * make the app read its private files on the sender's behalf.
     */
    fun parse(action: String?, type: String?, subject: String?, text: String?, streams: List<SharedStream>,
              ownAuthority: String): SharedContent? {
        if (action != SEND && action != SEND_MULTIPLE) return null
        val (usable, unsafe) = streams.distinctBy { it.uri }.partition { isForeignContent(it.uri, ownAuthority) }
        val (pictures, others) = usable.partition { (it.mime ?: type).orEmpty().startsWith("image/") }
        val images = pictures.map { it.uri }.take(ImageAttachments.MAX_PER_MESSAGE)
        val files = others.map { it.uri }.take(FileAttachments.MAX_PER_MESSAGE)
        return SharedContent(draftText(subject, text), images, files, dropped = unsafe.size + usable.size - images.size - files.size)
    }

    /** Chrome shares the page title as the subject and the URL as the text; keep both, once each. */
    fun draftText(subject: String?, text: String?): String {
        val s = subject?.trim().orEmpty()
        val t = text?.trim().orEmpty()
        return when {
            s.isEmpty() || s in t -> t
            t.isEmpty() || t in s -> s
            else -> "$s\n$t"
        }
    }

    /** Shared text first, then whatever was already typed, so neither is lost. */
    fun mergeDraft(draft: String, shared: String): String =
        listOf(shared, draft).filter { it.isNotBlank() }.joinToString("\n")

    /** The copy folder (relative to the cache) behind one of our shared-copy uris, else null. */
    fun copyDir(uri: String, ownAuthority: String): String? {
        val path = uri.removePrefix("content://$ownAuthority/").takeIf { it != uri } ?: return null
        val parts = path.split('/')
        return if (parts.size == 3 && parts[0] == CACHE_DIR && parts[1].all(Char::isDigit) && parts[1].isNotEmpty()) "$CACHE_DIR/${parts[1]}" else null
    }

    private fun isForeignContent(uri: String, ownAuthority: String): Boolean =
        uri.startsWith("content://") && uri.removePrefix("content://").substringBefore('/') != ownAuthority
}

private fun isLink(text: String) = text.lines().any { it.trim().startsWith("http://") || it.trim().startsWith("https://") }

private fun count(n: Int, noun: String) = when (n) { 0 -> null; 1 -> "1 $noun"; else -> "$n ${noun}s" }
