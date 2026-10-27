package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.HistoryMapper
import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.displayUserText
import io.github.nideta231.hermesremote.data.withFileRefs
import io.github.nideta231.hermesremote.data.fileRefs
import io.github.nideta231.hermesremote.data.splitReplyMedia
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FilesAndJobsTest {
    @Test fun fileRefsFollowTheTypedText() {
        assertEquals("read this\n@file:attachments/a.pdf", withFileRefs("read this", listOf("@file:attachments/a.pdf")))
        assertEquals("@file:a.txt\n@file:b.txt", withFileRefs("", listOf("@file:a.txt", "@file:b.txt")))
        assertEquals("plain", withFileRefs("plain", emptyList()))
    }

    @Test fun fileRefsShowAsNamesInTheChat() {
        assertEquals("read this\n📎 report.pdf", displayUserText("read this\n@file:attachments/report.pdf"))
        assertEquals("📎 my notes.md", displayUserText("@file:\"/home/u/my notes.md\""))
        assertEquals("mail me@file:x later", displayUserText("mail me@file:x later"))  // not a ref line
    }

    @Test fun historyShowsTheSameLabelAsTheLiveBubble() {
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("row_id", 1).put("text", "sum it\n@file:attachments/q3.xlsx"))
        val item = HistoryMapper.map(msgs).single() as ChatItem.User
        assertEquals("sum it\n📎 q3.xlsx", item.text)
    }

    @Test fun backtickRefsAndContextWarningsAreHandled() {
        val raw = "fix this\n@file:`/h/attachments/produk papan system.xlsx`\n\n--- Context Warnings ---\n- @file:`/h/attachments/produk papan system.xlsx`: path is outside the allowed workspace"
        assertEquals("fix this\n📎 produk papan system.xlsx", displayUserText(raw))
        assertEquals(listOf("/h/attachments/produk papan system.xlsx"), fileRefs(raw))
        val item = HistoryMapper.map(JSONArray().put(JSONObject().put("role", "user").put("row_id", 2).put("text", raw))).single() as ChatItem.User
        assertEquals(listOf("/h/attachments/produk papan system.xlsx"), item.files)
    }

    @Test fun replyFilesBecomeCardsAndPicturesStayPictures() {
        val m = splitReplyMedia("Done:\nMEDIA:/w/Report/Rekap Komisi.xlsx\nMEDIA:/w/chart.png\n\n| a |")
        assertEquals("Done:\n\n| a |", m.text)
        assertEquals(listOf("/w/chart.png"), m.images)
        assertEquals(listOf("/w/Report/Rekap Komisi.xlsx"), m.files)
    }
}
