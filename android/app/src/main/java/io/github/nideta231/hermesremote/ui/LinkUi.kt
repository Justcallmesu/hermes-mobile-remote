package io.github.nideta231.hermesremote.ui

import io.github.nideta231.hermesremote.LinkOffer
import io.github.nideta231.hermesremote.PairedPc

/** Hosts [pc] can be linked with by hand: the other kind (bridge host / gateway host), not already linked. */
fun linkCandidates(pcs: List<PairedPc>, pc: PairedPc): List<PairedPc> = pcs.filter { it.gateway != pc.gateway && !it.linked }

/** What the host menu shows for a host: the "Gateway + Bridge" tag and Unlink only when linked, else "Link with..." if any candidate. */
data class LinkMenu(val tag: Boolean, val unlink: Boolean, val linkWith: Boolean)

fun linkMenu(pcs: List<PairedPc>, pc: PairedPc) =
    LinkMenu(tag = pc.linked, unlink = pc.linked, linkWith = !pc.linked && linkCandidates(pcs, pc).isNotEmpty())

/** Title, body and buttons of the link offer dialog. */
data class OfferText(val title: String, val body: String, val confirm: String, val dismiss: String = "Not now")

fun offerText(o: LinkOffer): OfferText =
    if (o.bridgeId == null) OfferText("Pair this PC's bridge too",
        "A bridge answers on ${o.name}. Pair it to link it with this gateway; you'll scan its QR code.", "Pair")
    else OfferText("Same PC?",
        "${o.name} and this gateway look like the same PC. Link them? Each keeps its own sign-in and chats.", "Link")
