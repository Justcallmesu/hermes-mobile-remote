package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.HistoryMapper
import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.messageTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class MessageTimeTest {
    private val wib = TimeZone.getTimeZone("Asia/Jakarta")
    private val now = 1_793_080_800_000L  // 2026-10-27 13:00 WIB

    @Test fun `today shows only the time`() = assertEquals("11:45", messageTime(now - 75 * 60_000L, now, wib))

    @Test fun `earlier this year shows the date`() = assertEquals("Oct 26, 13:00", messageTime(now - 86_400_000L, now, wib))

    @Test fun `another year shows the year`() = assertEquals("Oct 27 2025, 13:00", messageTime(now - 365L * 86_400_000L, now, wib))

    @Test fun `history rows carry their timestamp`() {
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("text", "hi").put("row_id", 1).put("timestamp", 1_793_080_800.5))
            .put(JSONObject().put("role", "assistant").put("text", "hello"))
        val items = HistoryMapper.map(msgs)
        assertEquals(1_793_080_800_500L, (items[0] as ChatItem.User).at)
        assertNull((items[1] as ChatItem.Assistant).at)
    }
}
