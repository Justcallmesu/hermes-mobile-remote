package io.github.nideta231.hermesremote.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** How the app connects to the active host. */
sealed interface HostConnection {
    /** A bridge host: the saved pairing and REST client, as before. */
    data object BridgePairing : HostConnection
    /** A gateway host holding tokens: open the socket with a ticket from them. */
    data class GatewayLogin(val credentials: GatewayCredentials) : HostConnection
    /** A gateway host without tokens: ask the user to sign in again. */
    data object NeedsSignIn : HostConnection
}

fun decideConnection(kind: HostKind, credentials: GatewayCredentials?): HostConnection = when {
    kind == HostKind.BRIDGE -> HostConnection.BridgePairing
    credentials != null -> HostConnection.GatewayLogin(credentials)
    else -> HostConnection.NeedsSignIn
}

/** What to connect with now, and the other way it may switch to (null when there is none). */
data class ConnectionPlan(val connection: HostConnection, val fallback: HostConnection?)

/** The least time between two switches of a linked host, so a flaky bridge cannot make the app flap. */
const val SWITCH_MIN_MS = 30_000L

/**
 * A linked host uses the bridge when it answers, else the gateway sign-in; neither gives sign-in. An unlinked host
 * keeps the rule of the two-argument [decideConnection]. [current] and [sinceSwitchMs] hold the way in use and how
 * long ago it was chosen: going back to a returned bridge waits until [SWITCH_MIN_MS] have passed.
 */
fun decideConnection(
    kind: HostKind,
    credentials: GatewayCredentials?,
    linked: Boolean,
    bridgeReachable: Boolean,
    current: HostConnection? = null,
    sinceSwitchMs: Long = Long.MAX_VALUE,
): ConnectionPlan {
    if (!linked) return ConnectionPlan(decideConnection(kind, credentials), null)
    val gateway = credentials?.let { HostConnection.GatewayLogin(it) }
    val bridge = HostConnection.BridgePairing
    val holdGateway = current is HostConnection.GatewayLogin && sinceSwitchMs < SWITCH_MIN_MS
    return if (bridgeReachable && !(holdGateway && gateway != null)) ConnectionPlan(bridge, gateway)
    else if (gateway != null) ConnectionPlan(gateway, bridge)
    else ConnectionPlan(HostConnection.NeedsSignIn, null)
}

/** What the connection row says is live; null when nothing is. */
fun viaLabel(c: HostConnection): String? = when (c) {
    HostConnection.BridgePairing -> "via Bridge"
    is HostConnection.GatewayLogin -> "via Gateway"
    HostConnection.NeedsSignIn -> null
}

/** What the root of the app shows. */
enum class Root { PAIR, APP, SIGN_IN }

/** [gatewaySignedOut]: null when the active host is not a gateway, else whether it needs signing in again. */
fun rootScreen(paired: Boolean, gatewaySignedOut: Boolean?, adding: Boolean): Root = when {
    adding -> Root.PAIR
    paired -> Root.APP
    gatewaySignedOut == null -> Root.PAIR
    gatewaySignedOut -> Root.SIGN_IN
    else -> Root.APP
}

/** A [GatewayAuth] from stored tokens that writes every refreshed token set back to [store]. */
fun gatewayAuthFor(c: GatewayCredentials, store: GatewayCredentialStore, http: HttpTransport): GatewayAuth =
    GatewayAuth(c.baseUrl, http, GatewayTokens(c.accessToken, c.refreshToken, c.expiresAt, c.provider)) { t ->
        store.save(GatewayCredentials(c.baseUrl, t.accessToken, t.refreshToken, t.expiresAt, t.provider))
    }

/** The socket of a gateway host. [baseUrl] is its identity (a ticket URL changes on every connect). */
fun buildGateway(http: OkHttpClient, baseUrl: String, auth: GatewayAuth): Gateway =
    Gateway(http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build(), baseUrl, GatewayTicketUrl(baseUrl, auth))
