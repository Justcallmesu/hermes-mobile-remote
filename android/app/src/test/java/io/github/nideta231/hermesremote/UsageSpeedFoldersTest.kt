package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.fmtClock
import io.github.nideta231.hermesremote.data.fmtTier
import io.github.nideta231.hermesremote.data.parseClaude
import io.github.nideta231.hermesremote.data.parseFolders
import io.github.nideta231.hermesremote.data.parseSpeed
import io.github.nideta231.hermesremote.data.shortPath
import io.github.nideta231.hermesremote.ui.sampleAt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class UsageSpeedFoldersTest {
    @Test fun `claude details parse tier, severity and extra usage`() {
        val c = parseClaude(JSONObject("""{"ok":true,"plan":"team","session":29,"tier":"default_claude_max_5x","locked_reason":null,
            "limits":[{"label":"Session (5h)","percent":29,"active":true,"severity":"warning","group":"session"}],
            "extra":{"enabled":true,"currency":"USD","used":12.5,"limit":50.0,"percent":25,"limit_reached":false,"disabled_reason":null}}"""))
        assertEquals("Max 5x", fmtTier(c.tier))
        assertNull(c.lockedReason)
        assertEquals("warning", c.limits.single().severity)
        assertEquals(12.5, c.extra!!.used!!, 0.0)
        assertEquals(50.0, c.extra!!.limit!!, 0.0)
        assertNull(fmtTier(null))
    }

    @Test fun `speed result parses`() {
        val s = parseSpeed(JSONObject("""{"ts":100,"ping_ms":12.5,"jitter_ms":null,"down_mbps":81,"up_mbps":58.6}"""))
        assertEquals(81.0, s.downMbps!!, 0.0)
        assertNull(s.jitterMs)
    }

    @Test fun `chart tap maps to the nearest sample`() {
        assertEquals(0, sampleAt(0f, 100, 11))
        assertEquals(5, sampleAt(50f, 100, 11))
        assertEquals(10, sampleAt(140f, 100, 11))
        assertEquals(0, sampleAt(30f, 100, 1))
    }

    @Test fun `clock formats in the given zone`() {
        assertEquals("01:00", fmtClock(3600, zone = ZoneOffset.UTC))
    }

    @Test fun `folder listing and short paths`() {
        val l = parseFolders(JSONObject("""{"path":"/home/u/Workspace","parent":"/home/u","home":"/home/u","git":false,
            "dirs":[{"name":"app","path":"/home/u/Workspace/app","git":true}],"truncated":false}"""))
        assertTrue(l.dirs.single().git)
        assertFalse(l.truncated)
        assertEquals("~/Workspace", shortPath(l.path, l.home))
        assertEquals("~", shortPath("/home/u", "/home/u"))
        assertEquals("/opt/x", shortPath("/opt/x", "/home/u"))
        assertEquals("/home/user2", shortPath("/home/user2", "/home/u"))
        assertNull(parseFolders(JSONObject("""{"path":"/","parent":null,"home":"/home/u","dirs":[]}""")).parent)
    }
}
