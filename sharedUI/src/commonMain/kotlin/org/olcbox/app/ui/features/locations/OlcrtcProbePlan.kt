package org.olcbox.app.ui.features.locations

import org.olcbox.app.net.LocationKind

/**
 * Which rooms the coordinator may be asked about, and what its answers mean for
 * the board's "key no longer valid" marks.
 *
 * Pure, because the rule is the interesting part and it was wrong: every olcRTC
 * location with a key was asked, including one the user typed in by hand. The
 * coordinator only knows the keys it issued, so it answered 404 for a private
 * room and the board printed KEY NO LONGER VALID · REFRESH THIS LIST over a
 * room that was connected and working — with no list to refresh, because a
 * custom location does not come from one.
 */
object OlcrtcProbePlan {

    /**
     * The (storageId, key) pairs worth asking about: olcRTC rooms that carry a
     * key **and** came from a server list. A location without a subscription was
     * added by hand, and the coordinator has no opinion about it.
     */
    fun targets(locations: List<LocationItem>): List<Pair<String, String>> =
        locations.mapNotNull { item ->
            val config = item.config ?: return@mapNotNull null
            if (config.kind != LocationKind.Olcrtc) return@mapNotNull null
            if (item.subscriptionUrl.isNullOrBlank()) return@mapNotNull null
            config.key.takeIf { it.isNotBlank() }?.let { item.storageId to it }
        }

    /**
     * The marks after a pass: the ones this pass found gone, plus any it did not
     * ask about that were already marked — minus everything it found alive.
     *
     * [probed] is what the pass covered, and a mark on anything outside it is
     * dropped: the location was deleted, or it is one we have stopped asking
     * about, and either way the mark can no longer be renewed or cleared. A
     * probe that simply got no answer leaves its mark where it was, exactly as
     * it leaves the seat count.
     */
    fun nextRevoked(
        previous: Set<String>,
        probed: Set<String>,
        alive: Set<String>,
        gone: Set<String>
    ): Set<String> = (previous intersect probed) - alive + gone
}
