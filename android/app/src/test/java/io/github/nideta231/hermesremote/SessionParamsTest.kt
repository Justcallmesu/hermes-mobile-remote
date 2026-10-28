package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.sessionParams
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionParamsTest {
    @Test fun createAndResumeCarrySameSourceAndKeepProfileAndCols() {
        val create = sessionParams("work")
        val resume = sessionParams("work").put("session_id", "s1")
        assertEquals("desktop", create.getString("source"))
        assertEquals(create.getString("source"), resume.getString("source"))
        listOf(create, resume).forEach {
            assertEquals("work", it.getString("profile"))
            assertEquals(96, it.getInt("cols"))
        }
    }
}
