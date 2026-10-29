package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.BridgeUpdateState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeUpdateTest {
    @Test fun parsesCheckAndLastOutcome() {
        val s = BridgeUpdateState.parse(JSONObject("""{"current":"v1.28.1","latest":"v1.29.0","available":true,"can_update":true,
            "reason":null,"status":{"state":"failed","error":"uv sync failed"}}"""))
        assertTrue(s.available && s.canUpdate && s.failed)
        assertEquals("Last update failed: uv sync failed", s.message)
        assertTrue(BridgeUpdateState.parse(JSONObject("""{"status":{"state":"running","step":"sync"}}""")).updating)
    }
}
