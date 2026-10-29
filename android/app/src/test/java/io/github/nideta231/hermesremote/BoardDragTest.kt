package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.ui.columnSlot
import io.github.nideta231.hermesremote.ui.dropSlot
import io.github.nideta231.hermesremote.ui.edgeScroll
import io.github.nideta231.hermesremote.ui.fullIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardDragTest {
    private val others = listOf("a", "b", "c", "d")

    @Test fun slotIsBeforeTheFirstCardBelowTheFinger() {
        val visible = listOf("a" to 100f, "b" to 200f, "c" to 300f, "d" to 400f)
        assertEquals(0, dropSlot(others, visible, 50f))
        assertEquals(2, dropSlot(others, visible, 250f))
        assertEquals(4, dropSlot(others, visible, 999f))
    }

    @Test fun slotCountsCardsScrolledOutOfView() {
        // a and b are scrolled off the top: the finger between c and d is slot 3, not 1.
        assertEquals(3, dropSlot(others, listOf("c" to 100f, "d" to 200f), 150f))
        // Below the last visible card while later cards are off screen: right after it.
        assertEquals(2, dropSlot(others, listOf("a" to 100f, "b" to 200f), 900f))
        assertEquals(0, dropSlot(emptyList(), emptyList(), 10f))
    }

    @Test fun filteredSlotMapsToTheFullColumn() {
        val all = listOf("a", "x", "b", "y", "c")
        val shown = listOf("a", "b", "c")
        assertEquals(0, fullIndex(shown, all, 0))
        assertEquals(2, fullIndex(shown, all, 1))
        assertEquals(5, fullIndex(shown, all, 3))
        assertEquals(5, fullIndex(emptyList(), all, 0))
    }

    @Test fun edgeScrollSpeedsUpTowardsTheEdges() {
        assertEquals(0f, edgeScroll(500f, 0f, 1000f, 100f, 10f), 0f)
        assertEquals(-10f, edgeScroll(0f, 0f, 1000f, 100f, 10f), 0f)
        assertEquals(-5f, edgeScroll(50f, 0f, 1000f, 100f, 10f), 0.001f)
        assertEquals(10f, edgeScroll(1200f, 0f, 1000f, 100f, 10f), 0f)
        assertEquals(5f, edgeScroll(950f, 0f, 1000f, 100f, 10f), 0.001f)
        assertEquals(0f, edgeScroll(10f, 0f, 150f, 100f, 10f), 0f) // too short to have edges
    }

    @Test fun columnLandsBetweenTheCentresAroundTheFinger() {
        val centers = listOf(100f, 400f, 700f)
        assertEquals(0, columnSlot(centers, 50f))
        assertEquals(2, columnSlot(centers, 500f))
        assertEquals(3, columnSlot(centers, 900f))
    }
}
