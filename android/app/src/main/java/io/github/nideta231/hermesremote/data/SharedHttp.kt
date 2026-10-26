package io.github.nideta231.hermesremote.data

import okhttp3.OkHttpClient

/** The app's one OkHttp client: bridge and gateway code derive from it, so they share one connection pool and dispatcher. */
object SharedHttp {
    val client: OkHttpClient = OkHttpClient()
}
