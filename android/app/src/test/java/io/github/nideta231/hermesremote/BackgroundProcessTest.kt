package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.BackgroundProcesses
import io.github.nideta231.hermesremote.ui.processDetail
import io.github.nideta231.hermesremote.ui.processHeader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundProcessTest {
    private val sample = JSONObject("""{"processes": [
        {"session_id": "proc_a", "command": "npm run build", "cwd": "/home/u/app", "pid": null,
         "uptime_seconds": 12, "status": "exited", "exit_code": 1, "output_preview": "x", "output_tail": "error TS2304\n"},
        {"session_id": "proc_b", "command": "npm run dev", "cwd": "/home/u/app", "pid": 4242,
         "uptime_seconds": 252, "status": "running", "output_preview": "ready", "output_tail": null},
        {"command": "no id, skipped"}
    ]}""")

    @Test fun parsesRunningFirstAndKeepsTheTail() {
        val l = BackgroundProcesses.parse(sample)
        assertEquals(listOf("proc_b", "proc_a"), l.map { it.id })
        assertTrue(l[0].running)
        assertEquals("ready", l[0].output)  // no tail: the preview
        assertEquals("error TS2304", l[1].output)
        assertTrue(l[1].failed)
        assertFalse(l[0].failed)
    }

    @Test fun describesEachRowAndTheHeader() {
        val l = BackgroundProcesses.parse(sample)
        assertEquals("running · 4:12 · pid 4242 · app", processDetail(l[0]))
        assertEquals("exited 1 · app", processDetail(l[1]))
        assertEquals("1 of 2 background processes running", processHeader(l))
        assertEquals("1 background process running", processHeader(l.take(1)))
        assertEquals("1 background process ended", processHeader(l.drop(1)))
    }

    @Test fun emptyOrOldHermesGivesNothing() {
        assertTrue(BackgroundProcesses.parse(JSONObject("{}")).isEmpty())
    }
}
