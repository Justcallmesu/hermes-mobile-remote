package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.LiveReducer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveKeyTest {
    private fun delta(t: String) = JSONObject().put("text", t)

    @Test fun repeatedSeqNeverRepeatsAKey() {
        // A new run restarts seqs: seq 38 arrives again while the old "live-38" is still shown.
        var items = LiveReducer.apply(emptyList(), "message.delta", delta("first"), "38")
        items = LiveReducer.apply(items, "message.complete", JSONObject().put("text", "first"), "39")
        items = LiveReducer.apply(items, "tool.start", JSONObject().put("name", "x"), "38")
        items = LiveReducer.apply(items, "message.complete", JSONObject().put("status", "error"), "38")
        val keys = items.map { it.key }
        assertEquals(keys.toSet().size, keys.size)
    }
}
