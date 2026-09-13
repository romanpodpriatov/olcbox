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
     * key, came from a server list, **and** came from the list this coordinator
     * issues.
     *
     * The first two rules were here already. The third was the missing one, and
     * it cost the same false accusation the other two were added to stop.
     * A 404 was read as "this key is revoked", but the coordinator answers 404
     * for two different facts: a key it issued and withdrew, and a key it never
     * issued. A room bought from a partner, or served by someone running their
     * own olcRTC node, is the second — and the board printed KEY NO LONGER
     * VALID over a room that was connected and carrying traffic.
     *
     * So the question is only asked where a 404 can mean revocation: where the
     * list that supplied the key is served by the host being asked.
     */
    fun targets(locations: List<LocationItem>, coordinatorBaseUrl: String): List<Pair<String, String>> {
        val coordinator = hostOf(coordinatorBaseUrl) ?: return emptyList()
        return locations.mapNotNull { item ->
            val config = item.config ?: return@mapNotNull null
            if (config.kind != LocationKind.Olcrtc) return@mapNotNull null
            val subscription = item.subscriptionUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!hostOf(subscription).equals(coordinator, ignoreCase = true)) return@mapNotNull null
            config.key.takeIf { it.isNotBlank() }?.let { item.storageId to it }
        }
    }

    /**
     * The host of a URL, without pulling a URL parser into common code for one
     * comparison. Returns null for anything that does not look like an absolute
     * URL, which then matches nothing.
     */
    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        val host = authority.substringAfterLast('@').substringBefore(':')
        return host.takeIf { it.isNotBlank() }
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
