package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.HistoryMapper
import io.github.nideta231.hermesremote.data.LiveReducer
import io.github.nideta231.hermesremote.data.VaultDraft
import io.github.nideta231.hermesremote.data.normalizeVaultOrigin
import io.github.nideta231.hermesremote.data.parseVaultItems
import io.github.nideta231.hermesremote.data.replyRemainder
import io.github.nideta231.hermesremote.data.turnPromptKey
import io.github.nideta231.hermesremote.data.vaultAddParams
import io.github.nideta231.hermesremote.data.vaultDraftProblem
import io.github.nideta231.hermesremote.data.withInflight
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEditVaultTest {
    private fun row(role: String, id: Long, text: String, ts: Double) =
        JSONObject().put("role", role).put("row_id", id).put("text", text).put("timestamp", ts)

    /** The bug: the running turn's prompt, already stored with a long reply, was added again at the bottom. */
    @Test fun resumedRunningTurnIsNotDuplicatedAtTheBottom() {
        val msgs = JSONArray()
            .put(row("user", 1, "old question", 100.0))
            .put(row("assistant", 2, "old answer", 101.0))
            .put(row("user", 3, "fix the build", 200.0))
            .put(row("assistant", 4, "Step one done. Running tests now.", 210.0))
            .put(JSONObject().put("role", "tool").put("name", "terminal").put("row_id", 5))
        val history = HistoryMapper.map(msgs)
        val inflight = JSONObject().put("user", "fix the build").put("streaming", true)
            .put("assistant", "Step one done. Running tests now.All green, shipping.")
        val items = withInflight(history, inflight, turnPromptKey(msgs, 199.5))
        assertEquals(1, items.count { it is ChatItem.User && it.text == "fix the build" })
        assertEquals("All green, shipping.", (items.last() as ChatItem.Assistant).text)
    }

    @Test fun sameTextFallbackWhenNoTimestamps() {
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("row_id", 9).put("text", "hello  there"))
        val items = withInflight(HistoryMapper.map(msgs), JSONObject().put("user", "hello there").put("assistant", "Hi"))
        assertEquals(1, items.count { it is ChatItem.User })
        assertEquals("Hi", (items.last() as ChatItem.Assistant).text)
    }

    @Test fun promptNotStoredYetIsStillShown() {
        val msgs = JSONArray().put(row("user", 1, "a", 100.0)).put(row("assistant", 2, "b", 101.0))
        val items = withInflight(HistoryMapper.map(msgs), JSONObject().put("user", "new one").put("assistant", "work"), turnPromptKey(msgs, 300.0))
        assertEquals(listOf("a", "new one"), items.filterIsInstance<ChatItem.User>().map { it.text })
    }

    @Test fun remainderSkipsPersistedChunks() {
        assertEquals(" tail", replyRemainder("one two tail", listOf("one", "two")))
        assertEquals("all new", replyRemainder("all new", emptyList()))
    }

    /** Screen off mid-reply: deltas were lost, the final text replaces the partial reply. */
    @Test fun finalTextCompletesACutOffReply() {
        val items = listOf(ChatItem.User("u", "q"), ChatItem.Assistant("a", "The answer starts", streaming = true))
        val done = LiveReducer.apply(items, "message.complete", JSONObject().put("text", "The answer starts here and ends well."), "9")
        assertEquals("The answer starts here and ends well.", (done.last() as ChatItem.Assistant).text)
        assertEquals(2, done.size)
    }

    @Test fun finalTextLeavesMultiStepRepliesAlone() {
        val items = listOf(ChatItem.User("u", "q"), ChatItem.Assistant("a1", "Looking."), ChatItem.Assistant("a2", "Done: it works", streaming = true))
        val done = LiveReducer.apply(items, "message.complete", JSONObject().put("text", "Done: it works"), "9")
        assertEquals(3, done.size)
    }

    @Test fun historyKeepsUserRowIdForEditing() {
        val user = HistoryMapper.map(JSONArray().put(row("user", 42, "hi", 1.0))).single() as ChatItem.User
        assertEquals(42L, user.rowId)
    }

    @Test fun vaultOriginsAreNormalised() {
        assertEquals("https://example.com", normalizeVaultOrigin("example.com/"))
        assertEquals("http://10.0.0.1:8069", normalizeVaultOrigin("http://10.0.0.1:8069/web/login"))
        assertNull(normalizeVaultOrigin(""))
    }

    @Test fun vaultDraftChecks() {
        assertNotNull(vaultDraftProblem(VaultDraft("login", "Odoo", "", mapOf("identifier" to "a", "password" to "b"))))
        assertNotNull(vaultDraftProblem(VaultDraft("login", "Odoo", "odoo.com", mapOf("identifier" to "a"))))
        assertNull(vaultDraftProblem(VaultDraft("login", "Odoo", "odoo.com", mapOf("identifier" to "a", "password" to "b"))))
        val p = vaultAddParams(VaultDraft("login", " Odoo ", "odoo.com", mapOf("identifier" to "a", "password" to "b", "otp_secret" to "")))
        assertEquals("https://odoo.com", p.getString("origin"))
        assertEquals("Odoo", p.getString("label"))
        assertFalse(p.getJSONObject("secret").has("otp_secret"))
    }

    @Test fun vaultListParsesMetadataOnly() {
        val items = parseVaultItems(JSONObject().put("items", JSONArray()
            .put(JSONObject().put("id", "vault_1").put("kind", "login").put("label", "Odoo").put("origin", "https://x.odoo.com")
                .put("identifier", "me@x.com").put("backend", "local").put("has_otp", true))
            .put(JSONObject().put("id", "op:2").put("kind", "login").put("label", "Bank").put("backend", "onepassword"))))
        assertTrue(items.first { it.id == "vault_1" }.editable)
        assertFalse(items.first { it.id == "op:2" }.editable)
    }
}
