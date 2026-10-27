package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.TermKeys
import io.github.nideta231.hermesremote.data.TermScreen
import io.github.nideta231.hermesremote.data.fmtRate
import io.github.nideta231.hermesremote.data.fmtResetsIn
import io.github.nideta231.hermesremote.data.fmtUptime
import io.github.nideta231.hermesremote.data.parseClaude
import io.github.nideta231.hermesremote.data.parseHistory
import io.github.nideta231.hermesremote.data.parseNow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PcMonitorTest {
    @Test fun `monitor now parses stats, top and claude`() {
        val now = parseNow(JSONObject("""{"host":"pc","stats":{"cpu":12.5,"mem":40,"swap":0,"temp":null,"disk":55,
            "load1":1.2,"rx":2048,"tx":10,"rd":0,"wr":0,"mem_used_gb":6.4,"mem_total_gb":16,"uptime_s":90000,"cores":8},
            "core_pct":[10,20],"top":[{"pid":42,"name":"python","cpu":3.2,"mem_mb":120}],
            "claude":{"ok":true,"plan":"max","session":33,"weekly":12,"session_resets_at":"2026-10-26T18:00:00Z",
              "limits":[{"label":"Session (5h)","percent":33,"active":true}],"ts":1793000000},"alerts":["RAM above 95%"]}"""))
        assertEquals("pc", now.host)
        assertEquals(12.5, now.stats.cpu, 0.0)
        assertNull(now.stats.temp)
        assertEquals(8, now.stats.cores)
        assertEquals(listOf(10.0, 20.0), now.corePct)
        assertEquals(42, now.top.single().pid)
        assertTrue(now.claude.ok && now.claude.hasNumbers)
        assertEquals("max", now.claude.plan)
        assertEquals(listOf("RAM above 95%"), now.alerts)
    }

    @Test fun `claude error keeps the reason and no numbers`() {
        val c = parseClaude(JSONObject("""{"ok":false,"error":"Claude Code login expired","plan":null}"""))
        assertFalse(c.hasNumbers)
        assertNull(c.plan)
        assertEquals("Claude Code login expired", c.error)
    }

    @Test fun `history keeps gaps as null`() {
        val h = parseHistory(JSONObject("""{"range":"6h","ts":[1,2,3],"cpu":[1.5,null,3],"cpu_max":[2,null,4],"bucket":60}"""))
        assertEquals("6h", h.range)
        assertEquals(listOf(1.5, null, 3.0), h["cpu"])
        assertEquals(emptyList<Double?>(), h["nope"])
    }

    @Test fun `formatting`() {
        assertEquals("2 KB/s", fmtRate(2048.0))
        assertEquals("512 B/s", fmtRate(512.0))
        assertEquals("1.5 MB/s", fmtRate(1_500_000.0).replace(',', '.'))
        assertEquals("1d 1h", fmtUptime(90000))
        assertEquals("5m", fmtUptime(300))
        val now = Instant.parse("2026-10-26T10:00:00Z")
        assertEquals("resets in 2h 30m", fmtResetsIn("2026-10-26T12:30:00Z", now))
        assertEquals("resets in 1d 2h", fmtResetsIn("2026-10-27T12:00:00+00:00", now))
        assertEquals("", fmtResetsIn(null, now))
    }

    @Test fun `terminal frames update rows, cursor and scrollback`() {
        var s = TermScreen(cols = 10, rows = 3, lines = List(3) { emptyList() })
        s = s.apply(JSONObject("""{"type":"screen","cols":10,"rows":3,"full":true,"cursor":[2,1,true],
            "lines":{"0":[["$ ls","default","default",0]],"1":[["ok","green","default",1]],"2":[]}}"""))
        assertEquals("$ ls", s.lines[0].single().text)
        assertTrue(s.lines[1].single().bold)
        assertEquals(2 to 1, s.cursorX to s.cursorY)
        s = s.apply(JSONObject("""{"type":"screen","cols":10,"rows":3,"full":false,"cursor":[0,2,false],
            "lines":{"2":[["next","default","default",0]]},"scrolled":[[["old","default","default",0]]]}"""))
        assertEquals("$ ls", s.lines[0].single().text) // untouched row kept
        assertEquals("next", s.lines[2].single().text)
        assertEquals("old", s.scrollback.single().single().text)
        assertFalse(s.cursorVisible)
        // A resize starts from a blank screen of the new size.
        s = s.apply(JSONObject("""{"type":"screen","cols":20,"rows":5,"full":true,"cursor":[0,0,true],"lines":{}}"""))
        assertEquals(5, s.lines.size)
        assertTrue(s.lines.all { it.isEmpty() })
        assertEquals(1, s.scrollback.size)
    }

    @Test fun `ctrl keys`() {
        assertEquals("\u0003", TermKeys.ctrl('c'))
        assertEquals("\u0003", TermKeys.ctrl('C'))
        assertEquals("\u001a", TermKeys.ctrl('z'))
        assertEquals("1", TermKeys.ctrl('1'))
    }
}
