package org.olcbox.app.ui.components.kit

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.SubscriptionSort
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.OlcrtcSlots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PkBoardModelTest {

    // ── wire shape ─────────────────────────────────────────────────────────

    @Test
    fun anOlcrtcRoomNamesItsCarrier() {
        val config = LocationConfig(
            id = "room-1",
            key = "k",
            kind = LocationKind.Olcrtc,
            bypassProvider = "telemost",
            transport = "vp8channel"
        )
        assertEquals("a Telemost media session", wireShape(config))
    }

    @Test
    fun realityIsTheOneThatLooksLikeARealSite() {
        val config = LocationConfig(
            kind = LocationKind.Vless,
            rawLink = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                "?security=reality&pbk=abcdef&sni=www.microsoft.com&fp=chrome#NL"
        )
        assertEquals("a TLS handshake to a real website", wireShape(config))
    }

    @Test
    fun hysteria2IsUdp() {
        val config = LocationConfig(
            kind = LocationKind.Hysteria2,
            rawLink = "hysteria2://pass@example.com:443?obfs=salamander&obfs-password=x#NL"
        )
        assertEquals("obfuscated QUIC over UDP", wireShape(config))
    }

    @Test
    fun aVlessLinkWithNoRealityKeyIsPlainHttps() {
        val config = LocationConfig(
            kind = LocationKind.Vless,
            rawLink = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                "?security=tls&sni=example.com#DE"
        )
        assertEquals("ordinary HTTPS", wireShape(config))
    }

    @Test
    fun nothingSelectedStillReadsAsASentence() {
        assertEquals("an encrypted tunnel", wireShape(null))
    }

    // ── seats ──────────────────────────────────────────────────────────────

    @Test
    fun aRoomDrawsOnePipPerSeat() {
        val display = seatDisplay(OlcrtcSlots(slots_total = 8, slots_free = 5))
        val pips = (display as SeatDisplay.Pips).seats
        assertEquals(8, pips.size)
        assertEquals(3, pips.count { it == SeatState.Taken })
        assertEquals(5, pips.count { it == SeatState.Free })
        assertEquals(0, pips.count { it == SeatState.Mine })
    }

    @Test
    fun yourOwnSeatIsAlwaysDrawnFirst() {
        val display = seatDisplay(
            OlcrtcSlots(slots_total = 8, slots_free = 2, holds_slot = true)
        )
        val pips = (display as SeatDisplay.Pips).seats
        assertEquals(SeatState.Mine, pips.first())
        assertEquals(1, pips.count { it == SeatState.Mine })
        assertEquals(5, pips.count { it == SeatState.Taken })
        assertEquals(2, pips.count { it == SeatState.Free })
    }

    @Test
    fun holdingASeatOnANodeThatReportsNoneTakenStillSeatsYou() {
        // Not hypothetical: presence and capacity are two different reads on the
        // server, and they can disagree — notably for the five minutes after you
        // leave, when presence still counts you and capacity no longer does.
        val display = seatDisplay(
            OlcrtcSlots(slots_total = 4, slots_free = 4, holds_slot = true)
        )
        val pips = (display as SeatDisplay.Pips).seats
        assertEquals(SeatState.Mine, pips.first())
        assertEquals(3, pips.count { it == SeatState.Free })
    }

    @Test
    fun theCountAgreesWithThePipsBesideIt() {
        // The bug this exists to prevent, seen on a phone: the card drew a lime
        // seat and printed "0 / 8" next to it, because the pips read the
        // presence-corrected figure and the text read the raw one.
        val slots = OlcrtcSlots(slots_total = 8, slots_free = 8, holds_slot = true)
        val filled = (seatDisplay(slots) as SeatDisplay.Pips)
            .seats.count { it != SeatState.Free }
        assertEquals("$filled / 8", seatCountText(slots))
        assertEquals("1 / 8", seatCountText(slots))
    }

    @Test
    fun theCountIsTheServersFigureWhereNothingContradictsIt() {
        assertEquals("3 / 8", seatCountText(OlcrtcSlots(slots_total = 8, slots_free = 5)))
        assertNull(seatCountText(null))
        assertNull(seatCountText(OlcrtcSlots(slots_total = 0, slots_free = 0)))
    }

    @Test
    fun capacityLoweredBelowUseDoesNotDrawNegativeSeats() {
        val display = seatDisplay(OlcrtcSlots(slots_total = 4, slots_free = 9))
        val pips = (display as SeatDisplay.Pips).seats
        assertEquals(4, pips.size)
        assertTrue(pips.all { it == SeatState.Free })
    }

    @Test
    fun aBigNodeBecomesABarRatherThanABarcode() {
        val display = seatDisplay(OlcrtcSlots(slots_total = 64, slots_free = 16))
        val bar = display as SeatDisplay.Bar
        assertEquals(0.75f, bar.fraction)
        assertEquals(false, bar.mine)
    }

    @Test
    fun somethingWithNoSeatsDrawsNothing() {
        assertEquals(SeatDisplay.None, seatDisplay(null))
        assertEquals(SeatDisplay.None, seatDisplay(OlcrtcSlots(slots_total = 0, slots_free = 0)))
    }

    @Test
    fun freeTextCountsDownAndThenSaysFull() {
        assertEquals("5 free", seatFreeText(OlcrtcSlots(slots_total = 8, slots_free = 5)))
        assertEquals("full", seatFreeText(OlcrtcSlots(slots_total = 8, slots_free = 0)))
        assertNull(seatFreeText(null))
    }

    // ── occupancy history ──────────────────────────────────────────────────

    @Test
    fun oneSampleDrawsItsLevelRatherThanNothing() {
        // This asserted the opposite until a phone showed what it meant: no
        // second sample arrives for forty-five seconds, so every card was blank
        // for the first three quarters of a minute and read as broken. One
        // reading is a true statement about the level, which is what the height
        // of the line says.
        val flat = sparklinePoints(listOf(0.5f), 54f, 16f)
        assertEquals(2, flat.size)
        assertEquals(0f, flat[0].x)
        assertEquals(54f, flat[1].x)
        assertEquals(flat[0].y, flat[1].y)
    }

    @Test
    fun aRoomStandingStillDrawsHigherThanAnEmptyOne() {
        // "The line is flat like the patient is dead" — a steady room and an
        // empty one are both flat, and only the height tells them apart.
        val busy = sparklinePoints(listOf(0.75f, 0.75f), 54f, 16f)
        val empty = sparklinePoints(listOf(0f, 0f), 54f, 16f)
        assertTrue(busy[0].y < empty[0].y, "a fuller room must sit higher")
    }

    @Test
    fun noSamplesAtAllDrawsNothing() {
        assertTrue(sparklinePoints(emptyList(), 54f, 16f).isEmpty())
    }

    @Test
    fun aFullerRoomDrawsHigher() {
        val points = sparklinePoints(listOf(0f, 1f), 54f, 16f)
        assertEquals(2, points.size)
        assertEquals(0f, points[0].x)
        assertEquals(54f, points[1].x)
        assertTrue(points[1].y < points[0].y, "1.0 must sit above 0.0")
    }

    @Test
    fun theLineIsInsetSoAStrokeAtTheEdgeIsNotClipped() {
        val points = sparklinePoints(listOf(1f, 1f), 54f, 16f, inset = 2f)
        assertTrue(points.all { it.y >= 2f }, "top of the line stays inside the box")
    }

    @Test
    fun historyKeepsTheMostRecentSamplesOnly() {
        var history = emptyList<Float>()
        repeat(OCCUPANCY_HISTORY_LIMIT + 5) {
            history = appendOccupancy(history, OlcrtcSlots(slots_total = 8, slots_free = 4))
        }
        assertEquals(OCCUPANCY_HISTORY_LIMIT, history.size)
        assertTrue(history.all { it == 0.5f })
    }

    @Test
    fun theTraceRecordsTheSameOccupancyTheSeatsShow() {
        val slots = OlcrtcSlots(slots_total = 8, slots_free = 8, holds_slot = true)
        assertEquals(listOf(1f / 8f), appendOccupancy(null, slots))
    }

    @Test
    fun anUnmeteredNodeRecordsZeroRatherThanDividingByIt() {
        val history = appendOccupancy(null, OlcrtcSlots(slots_total = 0, slots_free = 0))
        assertEquals(listOf(0f), history)
    }

    // ── the action bar ─────────────────────────────────────────────────────

    private fun action(
        requiresSetup: Boolean = false,
        isConnected: Boolean = false,
        isConnecting: Boolean = false,
        selectedIsRoom: Boolean = true,
        selectedIsFull: Boolean = false,
        exitName: String? = "Netherlands"
    ) = boardAction(
        requiresSetup, isConnected, isConnecting, selectedIsRoom, selectedIsFull, exitName
    )

    @Test
    fun theButtonNamesWhatItWillJoin() {
        assertEquals(PkAction("TAKE A SEAT IN NETHERLANDS", PkActionKind.Go), action())
    }

    @Test
    fun somethingWithoutSeatsIsConnectedToRatherThanSatIn() {
        assertEquals(
            PkAction("CONNECT VIA NETHERLANDS", PkActionKind.Go),
            action(selectedIsRoom = false)
        )
    }

    @Test
    fun aLiveSessionOffersToLeaveTheRoomItIsIn() {
        assertEquals(
            PkAction("LEAVE NETHERLANDS", PkActionKind.Stop),
            action(isConnected = true)
        )
    }

    @Test
    fun connectingBeatsConnected() {
        // Both flags are set for a moment while a session tears down and rebuilds.
        assertEquals(
            PkAction("CANCEL", PkActionKind.Busy),
            action(isConnected = true, isConnecting = true)
        )
    }

    @Test
    fun aFullRoomIsNeverOfferedAsSomethingToJoin() {
        assertEquals(PkAction("ROOM IS FULL", PkActionKind.Blocked), action(selectedIsFull = true))
    }

    @Test
    fun aFullRoomYouAreAlreadyInStillOffersToLeaveIt() {
        assertEquals(
            PkAction("LEAVE NETHERLANDS", PkActionKind.Stop),
            action(isConnected = true, selectedIsFull = true)
        )
    }

    @Test
    fun nothingImportedAsksForAServerList() {
        assertEquals(
            PkAction("ADD SERVER LIST", PkActionKind.Go),
            action(requiresSetup = true)
        )
    }

    @Test
    fun anUnnamedExitStillProducesAButtonThatReads() {
        assertEquals(PkAction("TAKE A SEAT", PkActionKind.Go), action(exitName = null))
        assertEquals(PkAction("TAKE A SEAT", PkActionKind.Go), action(exitName = "   "))
        assertEquals(
            PkAction("DISCONNECT", PkActionKind.Stop),
            action(isConnected = true, exitName = null)
        )
    }

    @Test
    fun aLongExitNameIsCutAtASeparatorRatherThanMidWord() {
        assertEquals("Netherlands", shortenExitName("Netherlands-Amsterdam-03"))
        assertEquals("Frankfurt am", shortenExitName("Frankfurt am Main Datacenter", max = 14))
    }

    @Test
    fun aNameThatIsOneLongWordFallsBackToAnEllipsis() {
        assertEquals("Abcdefghijklm…", shortenExitName("Abcdefghijklmnopqrstuvwxyz", max = 14))
    }

    @Test
    fun aNameThatFitsIsLeftAlone() {
        assertEquals("Netherlands", shortenExitName("Netherlands"))
    }

    // ── board head ─────────────────────────────────────────────────────────

    @Test
    fun theHeadingOnlySaysRoomsWhereThereAreSeats() {
        assertEquals("Rooms", boardHeading(hasRooms = true))
        assertEquals("Servers", boardHeading(hasRooms = false))
    }

    @Test
    fun sortCyclesBackToWhereItStarted() {
        var sort = SubscriptionSort.None
        assertEquals("AS SERVED", sortLabel(sort))
        sort = nextSort(sort)
        assertEquals("PING", sortLabel(sort))
        sort = nextSort(sort)
        assertEquals("A–Z", sortLabel(sort))
        assertEquals(SubscriptionSort.None, nextSort(sort))
    }

    // ── the plan bar ───────────────────────────────────────────────────────

    @Test
    fun readsWhatFormatByteSizeWrites() {
        assertEquals(1024L * 1024 * 1024, parseQuotaBytes("1.0 GB"))
        assertEquals(512L, parseQuotaBytes("512 B"))
        assertEquals((9.4 * 1024 * 1024).toLong(), parseQuotaBytes("9.4 MB"))
        assertEquals(1024L * 1024 * 1024 * 1024, parseQuotaBytes("1 TB"))
    }

    @Test
    fun readsTheSpellingsProvidersActuallySend() {
        assertEquals(parseQuotaBytes("300 GB"), parseQuotaBytes("300GB"))
        assertEquals(parseQuotaBytes("300 GB"), parseQuotaBytes("300 gb"))
        // Binary and decimal spellings are treated alike on purpose.
        assertEquals(parseQuotaBytes("1 GB"), parseQuotaBytes("1 GiB"))
        assertEquals(parseQuotaBytes("1.5 GB"), parseQuotaBytes("1,5 GB"))
    }

    @Test
    fun refusesAnythingItDoesNotUnderstand() {
        assertNull(parseQuotaBytes(null))
        assertNull(parseQuotaBytes(""))
        assertNull(parseQuotaBytes("unlimited"))
        assertNull(parseQuotaBytes("GB"))
        assertNull(parseQuotaBytes("300"))
        assertNull(parseQuotaBytes("-5 GB"))
        assertNull(parseQuotaBytes("lots of GB"))
    }

    @Test
    fun theBarFillsFromWhatIsSpent() {
        assertEquals(0.5f, planFraction("150 GB", "300 GB"))
        assertEquals(0f, planFraction("0 B", "300 GB"))
    }

    @Test
    fun spendingMoreThanThePlanStillStopsAtFull() {
        assertEquals(1f, planFraction("400 GB", "300 GB"))
    }

    @Test
    fun aPlanNobodyStatedGetsNoBarAtAll() {
        assertNull(planFraction(null, "300 GB"))
        assertNull(planFraction("150 GB", null))
        assertNull(planFraction("150 GB", "unlimited"))
        // Zero is not an allowance of nothing — it is the absence of one.
        assertNull(planFraction("150 GB", "0 B"))
    }
}
