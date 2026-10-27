package io.github.nideta231.hermesremote.data

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Why the sign-in redirect never produced a code: wrong state, timeout, or cancelled. */
class LoopbackException(message: String) : Exception(message)

/**
 * One-shot HTTP listener on 127.0.0.1 (random port) that catches the gateway's redirect after the
 * Custom Tab login. It serves exactly one request, accepts only the matching `state`, shows a
 * "return to the app" page and closes. [close] cancels a pending [awaitCode].
 */
class LoopbackListener(private val expectedState: String, private val timeoutMs: Long = 600_000) : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val redirectUri: String get() = "http://127.0.0.1:$port/cb"

    /** Blocks until the one request arrives. Returns the code; throws [LoopbackException] otherwise. */
    fun awaitCode(): String {
        try {
            server.soTimeout = timeoutMs.toInt()
            server.accept().use { sock ->
                sock.soTimeout = 5_000
                val line = sock.getInputStream().bufferedReader().readLine().orEmpty()
                val query = line.split(' ').getOrNull(1)?.substringAfter('?', "").orEmpty()
                val params = query.split('&').filter { it.contains('=') }.associate {
                    val (k, v) = it.split('=', limit = 2)
                    URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
                }
                val code = params["code"]
                val ok = params["state"] == expectedState && !code.isNullOrEmpty()
                reply(sock.getOutputStream(), ok)
                if (!ok) throw LoopbackException("The sign-in answer did not match this request. Try again.")
                return code!!
            }
        } catch (e: LoopbackException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw LoopbackException("Sign-in took too long. Try again.")
        } catch (e: java.io.IOException) {
            throw LoopbackException("Sign-in was cancelled.")
        } finally {
            close()
        }
    }

    private fun reply(out: java.io.OutputStream, ok: Boolean) {
        val msg = if (ok) "Signed in. You can return to the app." else "Sign-in failed. You can return to the app."
        val body = "<!doctype html><meta name=viewport content=\"width=device-width\"><title>Hermes Remote</title><p style=\"font-family:sans-serif;font-size:20px;padding:24px\">$msg</p>"
        val bytes = body.toByteArray()
        out.write("HTTP/1.1 ${if (ok) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    override fun close() {
        runCatching { server.close() }
    }
}

/** Validation of the gateway address typed in the sign-in form. */
object GatewayUrl {
    /** The cleaned URL, or [IllegalArgumentException] with the reason. */
    fun validate(raw: String): String {
        val clean = raw.trim().trimEnd('/')
        val u = try { URI(clean) } catch (e: Exception) { throw IllegalArgumentException("That is not a valid address") }
        val host = u.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("The address has no host")
        when (u.scheme) {
            "https" -> {}
            "http" -> require(Tailnet.isAllowedHost(host)) {
                "Plain http is only allowed for a Tailscale or local-network address. Use https."
            }
            else -> throw IllegalArgumentException("The address must start with https://")
        }
        return clean
    }

    fun hostName(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { "Gateway" }
}

/** [HttpTransport] over OkHttp: non-2xx replies are returned, only I/O failures throw. */
class OkHttpTransport(private val client: OkHttpClient = SharedHttp.client) : HttpTransport {
    override suspend fun post(url: String, bearer: String?, jsonBody: String): HttpReply = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        client.newCall(req).execute().use { HttpReply(it.code, it.body?.string().orEmpty()) }
    }

    override suspend fun send(method: String, url: String, bearer: String?, jsonBody: String?): HttpReply = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .method(method, jsonBody?.toRequestBody("application/json".toMediaType()))
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        client.newCall(req).execute().use { HttpReply(it.code, it.body?.string().orEmpty()) }
    }
}
