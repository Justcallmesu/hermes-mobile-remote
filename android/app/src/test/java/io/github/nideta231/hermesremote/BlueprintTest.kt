package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.Blueprint
import io.github.nideta231.hermesremote.data.BpOp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueprintTest {
    private val doc = JSONObject("""
    {"project":{"id":"p1","name":"Shop","path":"/x"},"version":3,"control":"hermes",
     "diagram":{"nodes":[{"id":"a","kind":"start","label":"Start","x":0,"y":0,"w":120,"h":56},
                         {"id":"b","kind":"feature","label":"Login","x":0,"y":120,"w":180,"h":64}],
                "edges":[{"id":"e","from":"a","to":"b","label":""}]},
     "board":{"columns":[{"id":"todo","name":"Todo"},{"id":"done","name":"Done"}],
              "cards":[{"id":"c1","column":"todo","title":"One","order":1,"tags":["auth"],"checklist":[{"text":"x","done":true},{"text":"y","done":false}],"priority":"high","due":null,"node":"b"},
                       {"id":"c2","column":"todo","title":"Two","order":2,"tags":[],"checklist":[]}]},
     "log":[{"at":1,"by":"hermes","text":"hi"}]}""")

    @Test fun parses() {
        val bp = Blueprint.parse(doc)
        assertTrue(bp.hermesControl)
        assertEquals(listOf("c1", "c2"), bp.cardsIn("todo").map { it.id })
        assertEquals(1 to 2, bp.cards[0].progress)
        assertEquals(null, bp.cards[0].due)
        assertEquals(listOf("auth"), bp.tags)
    }

    @Test fun previewMovesAndRemoves() {
        val bp = Blueprint.parse(doc)
        val moved = BpOp.preview(bp, BpOp.of("card.move", "id" to "c2", "column" to "todo", "index" to 0))
        assertEquals(listOf("c2", "c1"), moved.cardsIn("todo").map { it.id })
        val done = BpOp.preview(bp, BpOp.of("card.move", "id" to "c1", "column" to "done"))
        assertEquals(listOf("c1"), done.cardsIn("done").map { it.id })
        val gone = BpOp.preview(bp, BpOp.of("node.remove", "id" to "b"))
        assertTrue(gone.edges.isEmpty())
        val drag = BpOp.preview(bp, BpOp.of("node.update", "id" to "b", "x" to 40.0, "y" to 300.0))
        assertEquals(300f, drag.nodes.first { it.id == "b" }.y)
    }

    @Test fun opNullsAreJsonNull() {
        assertTrue(BpOp.of("card.update", "id" to "c1", "due" to null).isNull("due"))
    }
}
