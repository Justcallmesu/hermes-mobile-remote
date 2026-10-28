package io.github.nideta231.hermesremote.data

import org.json.JSONObject

/** The params `session.create` and `session.resume` share: profile, cols and the `desktop` source, so Hermes treats both alike. */
fun sessionParams(profile: String?): JSONObject =
    JSONObject().put("source", "desktop").put("cols", 96).apply { profileParam(profile)?.let { put("profile", it) } }
