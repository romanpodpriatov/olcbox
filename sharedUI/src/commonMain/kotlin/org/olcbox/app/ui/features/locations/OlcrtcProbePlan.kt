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
     *
     * Deliberately not narrowed any further. Occupancy is an enrichment that
     * costs one request and is wanted for every room on the board; the first
     * cut of the olcbox#21 fix filtered *this* list by the coordinator's host
     * and took the seat counts and the graphs down with it. What the host has
     * to gate is [revocable], not who gets asked.
     */
    fun targets(locations: List<LocationItem>): List<Pair<String, String>> =
        locations.mapNotNull { item ->
            val config = item.config ?: return@mapNotNull null
            if (config.kind != LocationKind.Olcrtc) return@mapNotNull null
            if (item.subscriptionUrl.isNullOrBlank()) return@mapNotNull null
            config.key.takeIf { it.isNotBlank() }?.let { item.storageId to it }
        }

    /**
     * Of those, the ones whose `404` may be read as "revoked".
     *
     * The coordinator answers `404` for two different facts: a key it issued
     * and withdrew, and a key it never issued. Only the first is revocation.
     * A room bought from a partner, or served by an operator running their own
     * node, is the second — and olcbox#21 is the board printing KEY NO LONGER
     * VALID · REFRESH THIS LIST across one of those while it carried traffic.
     *
     * So a `404` counts only where the list that supplied the key is served by
     * the host being asked. Everywhere else it means nothing, the room keeps
     * its seat count, and a key that really is dead is reported by the next
     * connection attempt instead.
     */
    fun revocable(locations: List<LocationItem>, coordinatorBaseUrl: String): Set<String> {
        val coordinator = hostOf(coordinatorBaseUrl) ?: return emptySet()
        return locations.mapNotNullTo(mutableSetOf()) { item ->
            val subscription = item.subscriptionUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
            item.storageId.takeIf { hostOf(subscription).equals(coordinator, ignoreCase = true) }
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
