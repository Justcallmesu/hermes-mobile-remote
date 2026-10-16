package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.HistoryMapper
import io.github.nideta231.hermesremote.data.LiveReducer
import io.github.nideta231.hermesremote.data.parseSecret
import io.github.nideta231.hermesremote.data.secretAnswer
import io.github.nideta231.hermesremote.data.splitReplyImages
import io.github.nideta231.hermesremote.data.splitUserImages
import io.github.nideta231.hermesremote.data.withInflight
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagesAndSecretsTest {
    @Test fun storedImageRefsBecomePicturesNotText() {
        val (text, images) = splitUserImages("what is this?\n@image:/home/u/.hermes/images/upload_1.png\n@image:\"/tmp/my pic.jpg\"")
        assertEquals("what is this?", text)
        assertEquals(listOf("/home/u/.hermes/images/upload_1.png", "/tmp/my pic.jpg"), images)
    }

    @Test fun theModelFacingFormIsCleanedToo() {
        val raw = "[The user attached an image: upload_1.png]\n[Examine it with the vision_analyze tool using image_url: /h/images/upload_1.png]\n\nfix this"
        assertEquals("fix this" to listOf("/h/images/upload_1.png"), splitUserImages(raw))
    }

    @Test fun plainTextIsUntouched() {
        assertEquals("email me at a@b.c" to emptyList<String>(), splitUserImages("email me at a@b.c"))
    }

    @Test fun historyShowsAnImageOnlyMessage() {
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("text", "@image:/h/images/a.png"))
        val user = HistoryMapper.map(msgs).single() as ChatItem.User
        assertEquals("", user.text)
        assertEquals(listOf("/h/images/a.png"), user.images)
    }

    @Test fun inflightTurnKeepsItsPicture() {
        val out = withInflight(emptyList(), JSONObject().put("user", "look\n@image:/h/images/b.png").put("assistant", ""))
        assertEquals(listOf("/h/images/b.png"), (out.single() as ChatItem.User).images)
    }

    @Test fun replyMediaLinesBecomePictures() {
        val (text, images) = splitReplyImages("Here it is:\nMEDIA:/home/u/out/chart.png\nMEDIA:/home/u/out/report.pdf")
        assertEquals("Here it is:", text)  // other files are drawn as file cards
        assertEquals(listOf("/home/u/out/chart.png"), images)
    }

    @Test fun vaultAndSudoPromptsAreAnswerableOnThePhone() {
        val p = JSONObject().put("session_id", "s1")
        for (m in listOf("sudo", "secret", "vault.unlock_prompt", "vault.save_login", "vault.code")) {
            assertTrue(m, parseSecret("srq-1", m, p) != null)
        }
        assertNull(parseSecret("srq-1", "terminal.read", p))  // desktop-only bridges stay unanswered
        assertEquals("Unlock 1Password", parseSecret("srq-1", "vault.unlock_prompt", JSONObject().put("display_name", "1Password"))!!.title)
    }

    @Test fun answersMatchWhatHermesReads() {
        val code = parseSecret("srq-1", "vault.code", JSONObject().put("site", "github.com"))!!
        assertEquals("123456", secretAnswer(code, "", "123456").getString("value"))
        val login = parseSecret("srq-2", "vault.save_login", JSONObject().put("origin", "https://x.com").put("site", "x.com"))!!
        assertTrue(login.login)
        val pair = JSONObject(secretAnswer(login, " me@x.com ", "pw").getString("value"))
        assertEquals("me@x.com", pair.getString("identifier"))
        assertEquals("pw", pair.getString("password"))
    }

    @Test fun aSecretPromptOpensThenResolvesWithoutKeepingTheValue() {
        val r = parseSecret("srq-9", "sudo", JSONObject().put("command", "pacman -S x"))!!
        var items = LiveReducer.withSecret(emptyList(), r)
        items = LiveReducer.withSecret(items, r)  // replayed on resume: still one
        assertEquals(1, items.size)
        assertEquals(r, LiveReducer.openSecret(items)?.request)
        items = LiveReducer.resolve(items, "srq-9", "sent")
        assertNull(LiveReducer.openSecret(items))
        assertEquals("sent", (items.single() as ChatItem.Secret).outcome)
    }
}
