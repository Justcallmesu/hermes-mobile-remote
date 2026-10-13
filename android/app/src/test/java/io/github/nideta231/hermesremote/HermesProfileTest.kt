package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.BridgeClient
import io.github.nideta231.hermesremote.data.DEFAULT_HERMES_PROFILE
import io.github.nideta231.hermesremote.data.HermesProfile
import io.github.nideta231.hermesremote.data.Pairing
import io.github.nideta231.hermesremote.data.parseProfiles
import io.github.nideta231.hermesremote.data.profileParam
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Hermes profiles: what the app reads from the bridge, and that every scoped call names the profile. */
class HermesProfileTest {
    private val server = MockWebServer()
    private lateinit var client: BridgeClient

    @Before fun setUp() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        client = BridgeClient(Pairing("http://127.0.0.1:${server.port}", "t", "hrb_abcdefghijklmnopqrstuvwxyz"),
            hostGuard = { _, _ -> true })
    }

    @After fun tearDown() = server.shutdown()

    private fun ok(body: String = "{}") = server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
    private fun sent() = server.takeRequest(5, TimeUnit.SECONDS)!!

    @Test fun parsesTheProfileListAndSkipsNamelessRows() {
        val list = parseProfiles(JSONObject("""{"profiles":[
            {"name":"default","is_default":true,"model":"opus","provider":"anthropic","display_name":"","description":""},
            {"name":"developer","display_name":"Dev","description":"Engineer","model":"sonnet"},
            {"display_name":"broken"}, {"name":""}]}"""))
        assertEquals(listOf("default", "developer"), list.map { it.name })
        assertEquals(HermesProfile("developer", "Dev", "Engineer", "sonnet", "", false), list[1])
        assertEquals("Dev", list[1].label)
        assertEquals("default", list[0].label)  // no display name: the id
        assertEquals(emptyList<HermesProfile>(), parseProfiles(JSONObject("{}")))
    }

    @Test fun theDefaultProfileIsNeverSentSoOldRequestsStayTheSame() {
        assertNull(profileParam(null))
        assertNull(profileParam(""))
        assertNull(profileParam(DEFAULT_HERMES_PROFILE))
        assertEquals("developer", profileParam("developer"))
    }

    @Test fun restCallsNameTheProfile() = runBlocking<Unit> {
        ok("""{"sessions":[],"total":0}""")
        client.sessions(100, 0, "developer")
        val list = sent().requestUrl!!
        assertEquals("/v1/sessions", list.encodedPath)
        assertEquals("developer", list.queryParameter("profile"))

        ok(); client.setPinned("s1", true, "developer")
        val pin = sent()
        assertEquals("PATCH", pin.method)
        assertEquals("developer", pin.requestUrl!!.queryParameter("profile"))
        assertEquals(true, JSONObject(pin.body.readUtf8()).getBoolean("pinned"))

        ok("""{"mode":"manual"}"""); assertEquals("manual", client.approvalMode("developer"))
        assertEquals("developer", sent().requestUrl!!.queryParameter("profile"))

        ok("""{"model":"sonnet","provider":"anthropic"}"""); client.defaultModel("developer")
        assertEquals("developer", sent().requestUrl!!.queryParameter("profile"))
    }

    @Test fun defaultProfileCallsCarryNoProfileParameter() = runBlocking<Unit> {
        ok("""{"sessions":[],"total":0}"""); client.sessions(100, 0, DEFAULT_HERMES_PROFILE)
        assertNull(sent().requestUrl!!.queryParameter("profile"))
        ok("""{"mode":"smart"}"""); client.approvalMode()
        assertNull(sent().requestUrl!!.queryParameter("profile"))
    }

    @Test fun listsProfilesFromTheBridge() = runBlocking<Unit> {
        ok("""{"profiles":[{"name":"default","is_default":true},{"name":"pm"}]}""")
        assertEquals(listOf("default", "pm"), client.profiles().map { it.name })
        assertEquals("/v1/profiles", sent().requestUrl!!.encodedPath)
    }

    @Test fun profilesStateKnowsWhichOneIsInUse() {
        val st = ProfilesState(listOf(HermesProfile("default", isDefault = true), HermesProfile("pm", "PM")), active = "pm")
        assertEquals("PM", st.current?.label)
        assertNull(ProfilesState(active = "pm").current)  // not listed yet
    }
}
