package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.instantMillis
import io.github.nideta231.hermesremote.data.parseCronJobs
import io.github.nideta231.hermesremote.data.parseCronRuns
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronJobTest {
    @Test fun readsTheJobsTheBridgeSends() {
        val jobs = parseCronJobs(JSONObject("""{"jobs": [
            {"id": "a1", "name": "Daily digest", "schedule_display": "every day at 08:00", "state": "scheduled",
             "next_run_at": "2026-10-15T08:00:00+07:00", "last_run_at": null, "last_status": "ok", "failure_streak": 0},
            {"id": "b2", "name": "", "state": "paused", "last_status": "error", "last_error": "boom", "failure_streak": 2},
            {"name": "no id, skipped"}
        ]}"""))
        assertEquals(listOf("a1", "b2"), jobs.map { it.id })
        val digest = jobs[0]
        assertEquals("every day at 08:00", digest.schedule)
        assertEquals(1_792_026_000_000L, digest.nextRunAt)
        assertNull(digest.lastRunAt)
        assertFalse(digest.paused || digest.failing)
        val broken = jobs[1]
        assertEquals("b2", broken.name)  // no name: the id stands in
        assertTrue(broken.paused && broken.failing)
        assertEquals("boom", broken.lastError)
    }

    @Test fun readsRunsAndTheirTimes() {
        val runs = parseCronRuns(JSONObject("""{"runs": [
            {"id": "cron_a1_1", "title": "", "preview": "3 new mails", "started_at": 1792026000.5},
            {"title": "no id, skipped"}
        ]}"""))
        assertEquals(1, runs.size)
        assertEquals("Run", runs[0].title)
        assertEquals(1_792_026_000_500L, runs[0].startedAt)
    }

    @Test fun timesComeAsIsoStringsOrEpochSeconds() {
        assertEquals(1_792_026_000_000L, instantMillis("2026-10-15T01:00:00Z"))
        assertEquals(1_792_026_000_000L, instantMillis(1792026000))
        assertNull(instantMillis(""))
        assertNull(instantMillis("not a time"))
        assertNull(instantMillis(null))
        assertNull(instantMillis(JSONObject.NULL))
    }

    @Test fun anEmptyOrOddReplyIsNoJobs() {
        assertTrue(parseCronJobs(JSONObject("{}")).isEmpty())
        assertTrue(parseCronRuns(JSONObject("""{"runs": "nope"}""")).isEmpty())
    }
}
