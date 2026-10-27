package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.LiveReducer
import io.github.nideta231.hermesremote.data.multimodal
import io.github.nideta231.hermesremote.ui.readableToolResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolImagesTest {
    private val png = "data:image/png;base64,iVBORw0KGgo="
    private val result = """{"_multimodal":true,"content":[{"type":"text","text":"Image loaded."},""" +
        """{"type":"image_url","image_url":{"url":"$png"}}]}"""

    @Test fun splitsTextAndPictures() {
        val (text, images) = multimodal(result)!!
        assertEquals("Image loaded.", text)
        assertEquals(listOf(png), images)
        assertNull(multimodal("""{"output": "plain"}"""))
        assertNull(multimodal("not json"))
    }

    @Test fun toolRowGetsThePictureNotTheBase64() {
        var items = LiveReducer.apply(emptyList(), "tool.start", JSONObject("""{"name":"vision_analyze","tool_id":"t1"}"""), "1")
        items = LiveReducer.apply(items, "tool.complete", JSONObject().put("name", "vision_analyze").put("tool_id", "t1")
            .put("result", JSONObject(result)), "2")
        val t = items.filterIsInstance<ChatItem.Tool>().single()
        assertEquals(listOf(png), t.images)
        assertEquals("Image loaded.", t.result)
        // Same when the result arrives as text.
        items = LiveReducer.apply(emptyList(), "tool.complete", JSONObject().put("name", "vision_analyze").put("result", result), "3")
        assertEquals(listOf(png), items.filterIsInstance<ChatItem.Tool>().single().images)
        assertEquals("Image loaded.", readableToolResult(result))
    }
}
