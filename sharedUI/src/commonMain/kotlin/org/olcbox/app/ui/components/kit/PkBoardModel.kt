package org.olcbox.app.ui.components.kit

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.SubscriptionSort
import org.olcbox.app.net.OlcrtcSlots
import org.olcbox.app.net.TransportKind
import org.olcbox.app.net.transportKind

/**
 * Everything the board decides that is not drawing.
 *
 * Kept pure and in one file because a redesign is exactly the kind of change that
 * can be wrong without looking wrong — a seat count off by one, a bar that fills
 * on the wrong side, an action button that offers to join a room that is full.
 * None of that is visible in a screenshot; all of it is visible in a test.
 */

// ── what a card says goes on the wire ──────────────────────────────────────

/**
 * The one line the selected card adds about itself: what this connection looks
 * like to anything watching it.
 *
 * This is the sentence the whole redesign exists to make room for. Every other
 * client lists a protocol name; the protocol name says nothing to the person
 * choosing, and "a TLS handshake to a real website" is the actual claim Reality
 * makes. Sentence case here, uppercased by the caller that wants mono caps.
 */
fun wireShape(config: LocationConfig?): String {
    if (config == null) return "an encrypted tunnel"
    return when (config.transportKind()) {
        TransportKind.Olcrtc -> "a ${config.providerName()} media session"
        TransportKind.Hysteria2 -> "obfuscated QUIC over UDP"
        TransportKind.Xhttp -> "ordinary HTTP requests"
        TransportKind.Reality -> "a TLS handshake to a real website"
        TransportKind.Tls -> "ordinary HTTPS"
    }
}

/**
 * The one word beside a card's name: `VP8`, `Reality`, `Salamander`.
 *
 * The word, not the sentence. The card used to print the whole of
 * `protocolLabels()` here — "Telemost · VP8" — which on a name like "United
 * States" left the name itself about nine characters and ellipsised it. What the
 * longer form adds is already in the wire line under the selected card.
 */
fun transportTag(config: LocationConfig?): String? {
    if (config == null) return null
    if (config.transportKind() == TransportKind.Olcrtc) return config.transportName()
    return config.protocolLabels().lastOrNull()?.takeIf { it.isNotBlank() }
}

// ── seats ──────────────────────────────────────────────────────────────────

enum class SeatState {
    /** Yours. The only seat drawn in lime. */
    Mine,

    /** Somebody else's. */
    Taken,

    Free
}

/**
 * How a node's occupancy is drawn.
 *
 * Individual seats are the point — a room that holds eight people is a fact no
 * percentage communicates — but they stop being readable somewhere past a dozen
 * pips on a phone, so a large node degrades to the proportion instead of drawing
 * a barcode.
 */
sealed interface SeatDisplay {
    data class Pips(val seats: List<SeatState>) : SeatDisplay

    /** [fraction] is 0f..1f. [mine] colours it lime, as a pip would be. */
    data class Bar(val fraction: Float, val mine: Boolean) : SeatDisplay

    /** Not an olcRTC node, or one that has not answered. Draws nothing at all. */
    data object None : SeatDisplay
}

/** Above this many seats, pips become a bar. */
internal const val MAX_SEAT_PIPS = 16

/**
 * How many seats to treat as taken.
 *
 * `used` already clamps a capacity lowered below use. The `holds_slot` clause is
 * the other direction: the server answers presence and capacity with two
 * different reads and they disagree — notably for the five minutes after you
 * leave, when presence still counts you and the capacity figure no longer does.
 *
 * **Everything that shows occupancy must read this, not `slots.used`.** The pips
 * did and the count beside them did not, so a card drew your seat in lime next to
 * the words "0 / 8" — the card contradicting itself in the space of one line.
 */
fun effectiveUsed(slots: OlcrtcSlots): Int =
    if (slots.holds_slot) slots.used.coerceAtLeast(1) else slots.used

/** `1 / 8`. Null where there are no seats to count. */
fun seatCountText(slots: OlcrtcSlots?): String? {
    if (slots == null || slots.slots_total <= 0) return null
    return "${effectiveUsed(slots)} / ${slots.slots_total}"
}

fun seatDisplay(slots: OlcrtcSlots?): SeatDisplay {
    if (slots == null || slots.slots_total <= 0) return SeatDisplay.None
    val total = slots.slots_total
    val used = effectiveUsed(slots)
    if (total > MAX_SEAT_PIPS) {
        return SeatDisplay.Bar(
            fraction = (used.toFloat() / total).coerceIn(0f, 1f),
            mine = slots.holds_slot
        )
    }
    return SeatDisplay.Pips(
        List(total) { index ->
            when {
                // Yours is drawn first so it is in the same place on every card,
                // rather than wherever the server happened to seat you.
                index == 0 && slots.holds_slot -> SeatState.Mine
                index < used -> SeatState.Taken
                else -> SeatState.Free
            }
        }
    )
}

/** `7 free`, `full`, or nothing at all where there are no seats to count. */
fun seatFreeText(slots: OlcrtcSlots?): String? {
    if (slots == null || slots.slots_total <= 0) return null
    return if (slots.slots_free <= 0) "full" else "${slots.slots_free} free"
}

// ── occupancy history ──────────────────────────────────────────────────────

data class PkPoint(val x: Float, val y: Float)

/** How many occupancy samples a card's sparkline remembers. */
const val OCCUPANCY_HISTORY_LIMIT = 16

/**
 * A trace of how full a room has been, scaled into [width] x [height].
 *
 * A single sample draws a flat line at its level. That is not a fabricated
 * trend: the height of the line *is* the occupancy, and one reading is a true
 * statement about one moment. It used to draw nothing below two samples, which
 * meant every card was blank for the first three quarters of a minute after
 * launch — on a device that reads as broken, not as "nothing is known yet".
 *
 * y is inverted — a fuller room draws higher — and inset by a stroke's width at
 * each end so a line at 0 or 1 is not clipped by its own thickness.
 */
fun sparklinePoints(
    history: List<Float>,
    width: Float,
    height: Float,
    inset: Float = 1f
): List<PkPoint> {
    if (history.isEmpty() || width <= 0f || height <= 0f) return emptyList()
    val span = (height - inset * 2f).coerceAtLeast(0f)
    fun y(value: Float) = height - inset - value.coerceIn(0f, 1f) * span
    if (history.size == 1) {
        val level = y(history.single())
        return listOf(PkPoint(0f, level), PkPoint(width, level))
    }
    return history.mapIndexed { index, value ->
        PkPoint(x = width * index / (history.size - 1), y = y(value))
    }
}

/**
 * Appends a sample and drops the oldest beyond [OCCUPANCY_HISTORY_LIMIT].
 *
 * Reads [effectiveUsed], like the pips and the count: a trace that disagreed
 * with the seats drawn beside it would be the same bug in a third place.
 */
fun appendOccupancy(history: List<Float>?, slots: OlcrtcSlots): List<Float> {
    val value = if (slots.slots_total > 0) {
        (effectiveUsed(slots).toFloat() / slots.slots_total).coerceIn(0f, 1f)
    } else {
        0f
    }
    return ((history ?: emptyList()) + value).takeLast(OCCUPANCY_HISTORY_LIMIT)
}

// ── the action bar ─────────────────────────────────────────────────────────

enum class PkActionKind {
    /** Lime. Joining a room or connecting to a server. */
    Go,

    /** Red. Ending a live session. */
    Stop,

    /** Grey, with a spinner. */
    Busy,

    /** Grey, inert. Nothing here can be joined. */
    Blocked
}

data class PkAction(val label: String, val kind: PkActionKind)

/** Longest exit name the bar prints before it truncates. */
private const val ACTION_NAME_MAX = 18

/**
 * What the one button at the bottom says.
 *
 * It always names its object. "START" over a list of twelve servers is a button
 * that does not say what it will do, and a user who scrolled since selecting has
 * no way to check without scrolling back; "TAKE A SEAT IN NETHERLANDS" carries
 * the selection with it.
 *
 * [requiresSetup] is the caller's existing predicate, not a second opinion about
 * it — the bar must not offer to connect where the screen would refuse.
 */
fun boardAction(
    requiresSetup: Boolean,
    isConnected: Boolean,
    isConnecting: Boolean,
    selectedIsRoom: Boolean,
    selectedIsFull: Boolean,
    exitName: String?
): PkAction {
    val name = exitName?.trim()?.takeIf { it.isNotEmpty() }?.let(::shortenExitName)?.uppercase()
    return when {
        requiresSetup -> PkAction("ADD SERVER LIST", PkActionKind.Go)
        isConnecting -> PkAction("CANCEL", PkActionKind.Busy)
        isConnected -> PkAction(
            name?.let { "LEAVE $it" } ?: "DISCONNECT",
            PkActionKind.Stop
        )
        // Only blocks a room the user does not already hold a seat on; the caller
        // derives this from OlcrtcSlots.isBlocked, which knows the difference.
        selectedIsFull -> PkAction("ROOM IS FULL", PkActionKind.Blocked)
        selectedIsRoom -> PkAction(
            name?.let { "TAKE A SEAT IN $it" } ?: "TAKE A SEAT",
            PkActionKind.Go
        )
        else -> PkAction(
            name?.let { "CONNECT VIA $it" } ?: "CONNECT",
            PkActionKind.Go
        )
    }
}

/**
 * Trims an exit name to something a single-line button can hold.
 *
 * Cut at the last separator inside the budget where there is one, so
 * "Netherlands-Amsterdam-03" becomes "Netherlands" rather than "Netherlands-Amst…".
 */
internal fun shortenExitName(raw: String, max: Int = ACTION_NAME_MAX): String {
    val name = raw.trim()
    if (name.length <= max) return name
    val head = name.take(max)
    val cut = head.indexOfLast { it == ' ' || it == '-' || it == '·' || it == ',' }
    // A separator too near the start would leave a stub, which reads worse than
    // an ellipsis on a name that simply is one long word.
    //
    // [max] is the budget for the finished string, so the ellipsis comes out of
    // it rather than being added on top — the button has a width, not a
    // character count.
    return if (cut >= max / 2) head.take(cut).trimEnd() else name.take(max - 1).trimEnd() + "…"
}

// ── board head ─────────────────────────────────────────────────────────────

/**
 * `Rooms` where any of them is one, `Servers` where none is.
 *
 * The seat vocabulary is the app's own and it is worth using, but it is only true
 * of olcRTC. A list of nothing but Reality endpoints calling itself Rooms would
 * be the app describing itself as something it is not on that screen.
 */
fun boardHeading(hasRooms: Boolean): String = if (hasRooms) "Rooms" else "Servers"

fun sortLabel(sort: SubscriptionSort): String = when (sort) {
    SubscriptionSort.None -> "AS SERVED"
    SubscriptionSort.Ping -> "PING"
    SubscriptionSort.Alphabetical -> "A–Z"
}

fun nextSort(sort: SubscriptionSort): SubscriptionSort = when (sort) {
    SubscriptionSort.None -> SubscriptionSort.Ping
    SubscriptionSort.Ping -> SubscriptionSort.Alphabetical
    SubscriptionSort.Alphabetical -> SubscriptionSort.None
}

// ── the plan bar ───────────────────────────────────────────────────────────

private val QUOTA_UNITS = listOf(
    "TB" to 1024L * 1024 * 1024 * 1024,
    "GB" to 1024L * 1024 * 1024,
    "MB" to 1024L * 1024,
    "KB" to 1024L,
    "B" to 1L
)

/**
 * `"148.2 GB"` → bytes, or null where the provider sent something else.
 *
 * Providers write this header by hand and it shows: `148.2 GB`, `300GB`, `9.4 MiB`
 * and `1 TB` all appear. Binary and decimal spellings are read the same way — the
 * difference is 7% on a progress bar and no provider is consistent enough for the
 * distinction to mean anything.
 *
 * Null on anything unparseable, which is the whole point: the bar is drawn only
 * when both sides are understood, so a wrong bar is impossible rather than merely
 * unlikely.
 */
fun parseQuotaBytes(text: String?): Long? {
    val raw = text?.trim()?.uppercase()?.replace("IB", "B") ?: return null
    if (raw.isEmpty()) return null
    val unit = QUOTA_UNITS.firstOrNull { (suffix, _) -> raw.endsWith(suffix) } ?: return null
    val number = raw.dropLast(unit.first.length).trim().replace(',', '.')
    if (number.isEmpty()) return null
    val value = number.toDoubleOrNull() ?: return null
    if (value < 0 || value.isNaN() || value.isInfinite()) return null
    return (value * unit.second).toLong()
}

/**
 * How much of a plan is spent, 0f..1f, or null where no honest bar can be drawn.
 *
 * A plan of zero has no fraction — not 0%, not 100%. Drawing either would invent
 * a fact about an allowance the provider did not state.
 */
fun planFraction(used: String?, available: String?): Float? {
    val spent = parseQuotaBytes(used) ?: return null
    val total = parseQuotaBytes(available) ?: return null
    if (total <= 0L) return null
    return (spent.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
}
