package org.olcbox.app.ui.features.locations

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.LocationKind
import kotlin.test.Test
import kotlin.test.assertEquals

class OlcrtcProbePlanTest {

    private fun item(
        id: String,
        subscriptionUrl: String?,
        key: String = "key-$id",
        kind: LocationKind = LocationKind.Olcrtc
    ) = LocationItem(
        storageId = id,
        fullName = id,
        config = LocationConfig(
            id = "https://meet.example/$id",
            key = key,
            kind = kind,
            bypassProvider = "jitsi",
            transport = "datachannel"
        ),
        subscriptionUrl = subscriptionUrl
    )

    // The coordinator can only speak for keys it issued. A custom location's key
    // came from the person who typed it in, and the coordinator's 404 for it is
    // not "revoked" - it is "never heard of it". The board drew it as KEY NO
    // LONGER VALID · REFRESH THIS LIST over a room that was connected fine, with
    // no list to refresh.
    @Test
    fun aCustomLocationIsNeverAskedAboutOrMarked() {
        val targets = OlcrtcProbePlan.targets(
            listOf(item("sub", "https://proofkit.org/sub/t"), item("custom", null), item("blank", "  "))
        )
        assertEquals(listOf("sub" to "key-sub"), targets)
    }

    @Test
    fun onlyOlcrtcLocationsWithAKeyAreTargets() {
        val targets = OlcrtcProbePlan.targets(
            listOf(
                item("nokey", "https://proofkit.org/sub/t", key = ""),
                item("vless", "https://proofkit.org/sub/t", kind = LocationKind.Vless),
                item("ok", "https://proofkit.org/sub/t")
            )
        )
        assertEquals(listOf("ok" to "key-ok"), targets)
    }

    // A mark set by an earlier pass on a location that is no longer probed - it
    // was deleted, or it was custom all along - must not outlive the pass that
    // stopped asking about it.
    @Test
    fun aStaleMarkOnALocationNoLongerProbedIsDropped() {
        val next = OlcrtcProbePlan.nextRevoked(
            previous = setOf("custom", "sub"),
            probed = setOf("sub"),
            alive = emptySet(),
            gone = emptySet()
        )
        assertEquals(setOf("sub"), next)
    }

    @Test
    fun aliveClearsAndGoneMarks() {
        val next = OlcrtcProbePlan.nextRevoked(
            previous = setOf("a"),
            probed = setOf("a", "b", "c"),
            alive = setOf("a"),
            gone = setOf("b")
        )
        assertEquals(setOf("b"), next)
    }

    // An unanswered probe says nothing: a mark from before stays, exactly as the
    // seats do.
    @Test
    fun anUnansweredProbeKeepsThePreviousMark() {
        val next = OlcrtcProbePlan.nextRevoked(
            previous = setOf("a"),
            probed = setOf("a"),
            alive = emptySet(),
            gone = emptySet()
        )
        assertEquals(setOf("a"), next)
    }
}
