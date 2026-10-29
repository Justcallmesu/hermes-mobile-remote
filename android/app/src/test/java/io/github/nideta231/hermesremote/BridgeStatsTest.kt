package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.BridgeStats
import io.github.nideta231.hermesremote.data.Timing
import io.github.nideta231.hermesremote.data.formatSeconds
import io.github.nideta231.hermesremote.data.parseBridgeStats
import io.github.nideta231.hermesremote.data.tracks
import io.github.nideta231.hermesremote.ui.headline
import io.github.nideta231.hermesremote.ui.timingSuffix
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeStatsTest {
    /** The shape `GET /metrics?format=json` answers with (trimmed from a real bridge). */
    private val sample = JSONObject("""{
      "bridge_info": {"type": "gauge", "samples": [{"labels": {"version": "1.0.0", "protocol": "2"}, "value": 1.0}]},
      "process_start_time_seconds": {"type": "gauge", "samples": [{"labels": {}, "value": 1792051200.5}]},
      "bridge_paired_devices": {"type": "gauge", "samples": [{"labels": {}, "value": 2}]},
      "bridge_live_devices": {"type": "gauge", "samples": [{"labels": {}, "value": 1}]},
      "bridge_ws_connections": {"type": "gauge", "samples": [{"labels": {"endpoint": "live"}, "value": 1}, {"labels": {"endpoint": "terminal"}, "value": 0}]},
      "bridge_ws_connections_total": {"type": "counter", "samples": [{"labels": {"endpoint": "live"}, "value": 4}, {"labels": {"endpoint": "terminal"}, "value": 1}]},
      "bridge_http_requests_total": {"type": "counter", "samples": [
        {"labels": {"route": "/v1/me", "method": "GET", "status": "200"}, "value": 10},
        {"labels": {"route": "/metrics", "method": "GET", "status": "401"}, "value": 2}]},
      "bridge_http_request_seconds": {"type": "histogram", "samples": [
        {"labels": {"route": "/v1/me"}, "count": 10, "sum": 0.5, "buckets": {"0.01": 2, "0.05": 9, "0.1": 10, "1": 10}},
        {"labels": {"route": "/metrics"}, "count": 2, "sum": 0.02, "buckets": {"0.01": 2, "0.05": 2, "0.1": 2, "1": 2}}]},
      "bridge_relay_frames_forwarded_total": {"type": "counter", "samples": [
        {"labels": {"method": "prompt.submit"}, "value": 3}, {"labels": {"method": "session.list"}, "value": 7},
        {"labels": {"method": "response"}, "value": 50}]},
      "bridge_relay_frames_from_hermes_total": {"type": "counter", "samples": [{"labels": {}, "value": 120}]},
      "bridge_relay_frames_refused_total": {"type": "counter", "samples": [{"labels": {"reason": "method"}, "value": 1}]},
      "bridge_hermes_rpc_seconds": {"type": "histogram", "samples": [{"labels": {"method": "session.list"}, "count": 4, "sum": 0.024, "buckets": {"0.005": 1, "0.01": 4}}]},
      "bridge_hermes_rpc_errors_total": {"type": "counter", "samples": []},
      "bridge_questions_total": {"type": "counter", "samples": [{"labels": {"method": "approval"}, "value": 3}]},
      "bridge_questions_open": {"type": "gauge", "samples": [{"labels": {}, "value": 1}]},
      "bridge_question_wait_seconds": {"type": "histogram", "samples": [{"labels": {"method": "approval"}, "count": 2, "sum": 90, "buckets": {"30": 1, "60": 2}}]},
      "bridge_turns_started_total": {"type": "counter", "samples": [{"labels": {}, "value": 5}]},
      "bridge_turns_completed_total": {"type": "counter", "samples": [{"labels": {"status": "complete"}, "value": 3}, {"labels": {"status": "interrupted"}, "value": 1}]}
    }""")

    @Test fun readsWhatTheBridgeSends() {
        val s = parseBridgeStats(sample)
        assertEquals("1.0.0", s.version)
        assertEquals(1_792_051_200_500L, s.startedAt)
        assertEquals(2, s.pairedDevices)
        assertEquals(1, s.connectedDevices)
        assertEquals(1, s.liveSockets)
        assertEquals(12L, s.requests)
        assertEquals(2L, s.failedRequests)
        assertEquals(4L, s.connectionsSinceStart)  // live only, not the terminal
        assertEquals(60L, s.framesToHermes)
        assertEquals(1L, s.framesRefused)
        assertEquals(1, s.questionsOpen)
        assertEquals(4L, s.turnsFinished)
        // Replies to Hermes' questions are not "methods the phone used".
        assertEquals(listOf("session.list" to 7L, "prompt.submit" to 3L), s.topMethods)
    }

    @Test fun timingsMergeAcrossLabelsAndPickTheP95Bucket() {
        val s = parseBridgeStats(sample)
        assertEquals(12L, s.requestTiming.count)
        assertEquals(0.52 / 12, s.requestTiming.meanSec, 1e-9)
        assertEquals(0.1, s.requestTiming.p95Sec!!, 1e-9)    // 11 of 12 by 0.05 is < 95%, all 12 by 0.1
        assertEquals(45.0, s.questionWait.meanSec, 1e-9)
        assertEquals(0.01, s.rpcTiming.p95Sec!!, 1e-9)
    }

    @Test fun anEmptyOrOldReplyIsZeros() {
        val s = parseBridgeStats(JSONObject("{}"))
        assertEquals(BridgeStats(), s)
        assertTrue(s.rpcTiming.empty)
        assertNull(s.startedAt)
        assertEquals("", timingSuffix(s.rpcTiming))
    }

    @Test fun readsLikeTheCli() {
        assertEquals("42 ms", formatSeconds(0.042))
        assertEquals("3.1 s", formatSeconds(3.14))
        assertEquals("4 min", formatSeconds(240.0))
        assertEquals(" · avg 6 ms · p95 ≤ 10 ms", timingSuffix(Timing(4, 0.006, 0.01)))
        val h = headline(parseBridgeStats(sample))
        assertTrue(h, h.startsWith("v1.0.0 · up ") && h.endsWith("1 connected · Hermes 6 ms avg · 1 approval(s) waiting"))
    }
}

class TrackingParseTest {
    @org.junit.Test fun trackingDefaultsOnAndReadsOffGroups() {
        val s = io.github.nideta231.hermesremote.data.parseStats(org.json.JSONObject("""{"cpu":5,"tracking":{"network":false,"cpu":true}}"""))
        org.junit.Assert.assertTrue(s.tracks("cpu") && s.tracks("temp") && !s.tracks("network"))
        val old = io.github.nideta231.hermesremote.data.parseStats(org.json.JSONObject("""{"cpu":5}"""))
        org.junit.Assert.assertTrue(io.github.nideta231.hermesremote.data.TrackingGroups.all { old.tracks(it.first) })
    }
}
