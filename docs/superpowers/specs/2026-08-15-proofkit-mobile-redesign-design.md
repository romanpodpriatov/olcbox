# ProofKit Mobile redesign — design

**Date:** 2026-08-15
**Branch:** `feat/proofkit-mobile-redesign`
**Reference:** Claude Design project `ba9efe2a-a0f9-4369-acbb-960052d7bd5a`, file
`ProofKit Mobile.dc.html` (imported copy of the rendered prototype kept out of the
repo; every value it fixes is restated below so this document stands alone).

## Why

App Review rejected 1.0.270 under Guideline 4.3(a) — "shares a similar binary,
metadata, and/or concept as apps submitted by other developers". The metadata half
of that was answered in `docs/app-store-listing.md`. This is the other half: the
screen itself.

The home screen currently is the shape every sing-box front end ships — a centred
app bar over a 200dp circular power dial over a flat list of servers with a radio
dot on each row. A reviewer paging through V2Box, Streisand, Hiddify, FoXray and
us sees one layout five times. Nothing about that silhouette is ours, and the one
thing that is ours — olcRTC rooms with a fixed number of seats and live occupancy
— is currently a 3dp progress bar inside a row.

The redesign inverts that: the seat model becomes the subject of the screen, and
the power dial goes away.

**This is a design change only.** No VPN manager, engine, importer, link parser or
protocol code is touched. Every existing behaviour keeps working through the same
calls it uses today. Two additions reach below `ui/`, both additive and both
recorded here: the occupancy history buffer in `LocationViewModel` (§2) and the
onboarding flag on the persistence bundle (§6).

## Non-goals

- No change to what the app can do. No feature is removed, no call site is
  rewired to a different function.
- No change to `GeneratedAppInfo.NAME` (`"olcbox"` — it feeds the User-Agent and
  the updater), the iOS bundle id, `applicationId`, or any Intent action constant.
- No new third-party dependency.
- No light theme. Dark-only stays.
- No change to the wording of the VPN disclosure (see §9).

## Design tokens

The reference was built on the app's own tokens, so `Color.kt` and `Type.kt` need
no change at all. `PkPalette` gains the four the reference uses that we have no
name for:

| Token | Value | Used by |
|---|---|---|
| `seatFree` | `#22263A` | an empty seat pip |
| `seatOther` | `#4A5379` | a seat somebody else holds |
| `link` | `#8190FF` | `MEASURE`, inline links |
| `hairline` | `#14172A` | the rule under the board head, group wells |

Rule unchanged from the previous redesign: literals live in `Color.kt` and
`PkPalette.kt` only. Components read roles.

## 1. Screen skeleton

`StartButton.kt` is deleted. Its 200dp dial is the single most recognisable piece
of the clone silhouette, and nothing else on the screen has to move for it to go.

The home screen becomes five bands. Three are pinned, one scrolls, one is pinned
to the bottom:

```
┌──────────────────────────────────────────┐
│ ProofKit  OLCRTC CORE      ⏱   ＋   ⚙    │  header row      pinned
├──────────────────────────────────────────┤
│ ● NOT CONNECTED                       US │  status strip    pinned
│   Telemost · VP8                         │
│ ┌ notice, when there is one ───────────┐ │
├──────────────────────────────────────────┤
│ Rooms                          [↕ PING]  │  board head      pinned
│ (ALL)(OLCRTC)(VLESS)(HYSTERIA2)          │
├──────────────────────────────────────────┤
│ ▾ Encrypted list              upd 4m  ↻  │  board           scrolls
│   PLAN · RESETS IN 12D        148/500 GB │
│   ▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░             │
│  ┌────────────────────────────────────┐  │
│  │ US · Telemost  VP8   YOUR SEAT      │  │
│  │                    7 free    38 ms  │  │
│  │ ▮▮▮▯▯▯▯▯      ∿∿∿∿        1 / 8      │  │
│  │ ────────────────────────────────────│  │
│  │ A TELEMOST MEDIA SESSION    MEASURE │  │
│  └────────────────────────────────────┘  │
│  ┌ DE · WB Stream  SEI  … ─────────────┐  │
│  ＋ ADD SERVER LIST (dashed)              │
│  PROOFKIT · v1.0.284 · abc1234           │
├──────────────────────────────────────────┤
│ ▐          TAKE A SEAT IN US          ▌  │  action bar      pinned
└──────────────────────────────────────────┘
```

**Header row.** Not a `CenterAlignedTopAppBar`. `ProofKit` in Space Grotesk 19sp
beside a mono tag, and three 40dp square buttons (12dp radius, `#0F1117` on a
`#1E2030` border): diagnostics, add, settings. The 7-tap admin gesture moves to
the `ProofKit` word, where it is today. The lock button and the split-tunneling
button keep their slots on the platforms that ask for them.

The mono tag reads `OLCRTC CORE`, not the reference's `OLCBOX CORE`. The fork's
name in the UI buys nothing; olcRTC is the claim that answers 4.3.

**Status strip.** One horizontal card, replacing the centred stack of pill +
26sp timer + traffic + caption. Left: a dot (pulsing when active) over a mono
state label and a mono meta line. Right: one large mono value — the session
timer when connected, the exit's short name when not. Border turns lime while
active.

**Board head.** An `h2` naming the list and a sort button, then the filter chips,
then a `hairline` rule. The heading is `Rooms` when any location in the list is
olcRTC and `Servers` when none is — the seat vocabulary is only used where seats
exist.

The sort button cycles `AS SERVED → PING → A–Z` and writes
`SubscriptionSettings.sort`, the same field the Server lists screen sets. One
piece of state, two ways in.

Filter chips keep today's rule: they appear only when more than one transport is
present. A row containing one chip is noise.

**Action bar.** 56dp, 16dp radius, pinned above the bottom inset. It names what
it will do:

| State | Label | Background | Foreground |
|---|---|---|---|
| idle, olcRTC selected | `TAKE A SEAT IN <NAME>` | lime | `#0C1300` |
| idle, other transport | `CONNECT VIA <NAME>` | lime | `#0C1300` |
| connecting | `CANCEL` + spinner | `#1D2030` | `textDim` |
| connected | `LEAVE <NAME>` | `#3D141C` | `#FF9FB0` |
| selected room full | `ROOM IS FULL` | `#1D2030` | `textMuted` |
| nothing imported | `ADD SERVER LIST` | lime | `#0C1300` |

`<NAME>` is the location's short name, truncated to fit on one line. The bar's
`onClick` is exactly the branch `StartButton` runs today, disclosure gate
included.

## 2. Room card

`LocationRow`'s fixed 76dp row becomes `PkRoomCard`, which grows when selected.

- **Line 1** — name in Space Grotesk 15sp (lime when selected), a mono tag for
  the transport, an optional badge, then `N free` and the ping, right-aligned.
- **Line 2, olcRTC only** — seat pips, a sparkline, and `used / total`.
- **Line 3, selected only** — the wire shape in mono caps, and `MEASURE`.

The badge reads `YOUR SEAT` when this card is both selected and connected, and
`SELECTED` when it is selected and not.

**Seat pips** come from `OlcrtcSlots`: `slots_total` pips, `used` of them filled
with `seatOther`, the rest `seatFree`. When `holds_slot` is true the first filled
pip is lime — that one is yours. A location with `slots == null` renders no line 2
at all, which is exactly what it does today. `isBlocked` still refuses the tap and
still dims the card.

**Sparkline** — a 16-point ring buffer of `used / slots_total` per storage id,
appended inside the existing `refreshOlcrtcSlots()` (which already ticks every 45
seconds). Held in the `LocationViewModel`, never persisted; a cold start draws no
line until the second sample.

**Wire shape** is a pure function of `LocationConfig`:

| Kind / transport | Text |
|---|---|
| olcRTC | `A <PROVIDER> MEDIA SESSION` |
| Reality | `A TLS HANDSHAKE TO A REAL WEBSITE` |
| Hysteria2 | `OBFUSCATED QUIC OVER UDP` |
| XHTTP | `ORDINARY HTTP REQUESTS` |
| TLS | `ORDINARY HTTPS` |

**MEASURE** calls the existing `onRefreshClick(listOf(storageId))`. It is not a
new measurement path — it is the group's latency button scoped to one row. Where
the platform says a location cannot be measured, the existing snackbar explains
why, unchanged.

## 3. Group header and the plan bar

Chevron, name, meta line, then the group's buttons as 32dp squares in the header
style. The reference draws one button; we keep all four we have — provider page,
support, measure, refresh — because they work today and removing a control is not
a design change.

**Plan bar.** `LocationMetadata.subscription.used` / `.available` arrive as
strings (`"148.2 GB"`). A pure `parseQuotaBytes(String): Long?` reads a decimal
and a `B/KB/MB/GB/TB` suffix. Both sides parse → a 3dp bar under the group header
inside a sunken well, with `PLAN · RESETS IN <n>D` on the left and `used /
available` on the right, amber above 85%. Either side fails to parse → no bar,
and the existing text meta line stands alone. A subscription that reports no
quota is unchanged.

## 4. Settings becomes a screen

`ApplicationSettingsSheet` keeps its name, its parameter list and its 1369 lines
of behaviour. Only its outermost wrapper changes: `ModalBottomSheet` → a
full-bleed `Surface` with a back arrow and a `Settings` title. **No call site on
any platform changes**, which is what keeps this from becoming a rewrite.

Inside, sections gain a mono eyebrow and their rows move into a bordered card:
`PkToggleRow` (label, sub, 44×26 track) and `PkLinkRow` (label, sub, optional
value, chevron). A `REPLAY FIRST RUN` button and the version line close the screen.

## 5. Sheets

One `PkBottomSheet` — 26dp top radius, a 40×4 grab handle, a Space Grotesk title
over a mono subtitle — carries the add sheet, the log sheet and the update offer.
Their contents and callbacks do not change.

## 6. Onboarding

Three steps, shown once:

1. `WHAT THIS IS` — Carried over WebRTC
2. `HOW ROOMS WORK` — Every room has seats
3. `WHAT YOU BRING` — You bring the servers

An 8-segment ring illustrates seats filling. The final CTA opens the add sheet.
`SKIP` is always available.

It runs once. The flag rides the same rail as the disclosure timestamp:
`LocationBundleV4.onboardingSeenAt: Long?`, which that class already documents as
"the one thing already persisted identically on every platform". Repository gains
`isOnboardingSeen()` / `setOnboardingSeen(atMillis: Long?)` — nullable because
unlike consent this one is resettable, and `REPLAY FIRST RUN` in settings passes
null. This is the only data-layer addition in the whole change, and it is three
lines mirroring three that already exist.

The onboarding must not mention the VPN permission — Play requires the disclosure
to be its own consent and forbids combining it with another (see the comment atop
`VpnDisclosureScreen`).

## 7. Camera rationale

New. Today the QR scanner is invoked directly and the system permission prompt is
the first thing a user sees. A sheet now precedes it on platforms where
`canScanQr` is true: three bullets in our own words (the camera reads a
server-list QR code and nothing else; no photo or video is recorded, stored or
uploaded; declining leaves link, URI and file open), then `ALLOW AND SCAN` /
`NOT NOW`. Declining calls nothing.

## 8. Two-pane at ≥720dp

`BoxWithConstraints` on the home screen. At `maxWidth >= 720.dp` the board moves
into a left pane and the selected room gets a right pane — wire shape, seats,
ping, `MEASURE`, and the group's plan — with the action bar pinned under the
right pane. Below the threshold, the phone layout renders unchanged.

No `material3-window-size-class` dependency: a width breakpoint answers the same
question and works identically on desktop, which gets the layout for free.

App Review ran on an iPad Air 11-inch. A stretched phone layout reads as a
template on its own, which is why this ships in the same pass rather than after.

## 9. What the reference says and we do not do

Three deliberate departures, recorded so they are not "fixed" later:

1. **The disclosure text stays ours and stays platform-neutral.** The reference
   splits it into "iOS will ask permission" / "Android will ask permission". Ours
   says "your device will ask you" because `VpnDisclosureScreen` is commonMain and
   iOS renders it — that was a real bug fixed in `f1471d7`, days before this work.
   The sheet takes the reference's *form*; the words do not change.
2. **The group header keeps four buttons**, not one.
3. **The header tag is `OLCRTC CORE`**, not `OLCBOX CORE`.

The reference's `plainLanguage` and `storeSafeCopy` prototype toggles are not
built. They exist to compare copy in the browser; the app ships one wording.

## Testing

Pure functions get `jvmTest` coverage, because they are where a redesign can be
wrong without looking wrong:

- `wireShape(config)` over every `LocationKind` and `TransportKind`
- `parseQuotaBytes` — units, decimals, junk, empty, missing suffix
- the plan fraction, including `available == 0`
- seat-pip model from `OlcrtcSlots`, including `slots_total == 0`, `holds_slot`,
  and a capacity lowered below use
- the sparkline path builder — fewer than two points draws nothing
- action-bar label for each state in §1

Layout is verified by rendering the real Compose screens headless with
`ImageComposeScene` and reading the PNGs: 402×874 (iPhone), 412×892 (Android),
1024×1366 (iPad) across empty / idle / connecting / connected / full-room /
settings / each sheet / onboarding. The scratch render test is deleted before the
branch is pushed.

`:desktopApp:compileKotlin` is the compile gate for every commit.
