# ProofKit Mobile redesign — implementation plan

Spec: `docs/superpowers/specs/2026-08-15-proofkit-mobile-redesign-design.md`
Branch: `feat/proofkit-mobile-redesign`

Every step ends green on `./gradlew --no-daemon :sharedUI:jvmTest
:desktopApp:compileKotlin`. Gradle reporting UP-TO-DATE is not evidence — read
`sharedUI/build/test-results/jvmTest/TEST-*.xml`.

## Step 1 — tokens and pure logic

Nothing renders yet; this is the part that can be wrong invisibly, so it is tested
first.

- `PkPalette`: add `seatFree`, `seatOther`, `link`, `hairline`.
- `PkIcons`: add `SwapVert` (sort), `PhotoCamera`, `ArrowBack` if core lacks it.
- New `ui/components/kit/PkBoardModel.kt`, pure and platform-free:
  - `wireShape(config: LocationConfig): String`
  - `seatPips(slots: OlcrtcSlots?, mine: Boolean): List<SeatPip>`
  - `sparklinePath(points: List<Float>, width: Float, height: Float): String?`
  - `actionBarLabel(...)` returning label + role for each state in spec §1
  - `boardHeading(locations): String`
- `parseQuotaBytes(String): Long?` + `planFraction(used, available): Float?` beside
  the existing `quotaText` in `LocationSelection.kt`.
- Tests in `sharedUI/src/commonTest` covering each of the above, including
  `slots_total == 0`, capacity below use, `available == 0`, junk quota strings,
  and a single-point sparkline.

## Step 2 — the kit

New composables in `ui/components/kit/`, each rendered in isolation before being
wired in:

- `PkIconButton` (40dp square, 12dp radius) and `PkSmallIconButton` (32dp, 9dp)
- `PkHeaderRow` — brand, mono tag, action slot; carries the 7-tap gesture
- `PkStatusStrip`
- `PkBoardHead` — heading, sort button, chips slot, hairline
- `PkActionBar`
- `PkRoomCard` — the three lines of spec §2, including pips and sparkline
- `PkGroupHeader` + `PkPlanBar`
- `PkBottomSheet`, `PkSettingsCard`, `PkToggleRow`, `PkLinkRow`

## Step 3 — home screen on the new kit (phone)

Rebuild `HomeScreen` into the five bands. `StartButton.kt` is deleted in this
step, `LocationRow` is replaced by `PkRoomCard`, and `HomeScreenAppBar` by
`PkHeaderRow`. `LocationSelectorScreen` keeps its parameter list and its
grouping, sorting, filtering and collapse logic — only what it renders changes.

Checks that the behaviour survived: pull-to-refresh still on the list, the
disclosure gate still on the way up only, admin long-press still opens the
location editor, `isBlocked` still refuses the tap, empty state still reachable.

## Step 4 — occupancy history, MEASURE, plan bar

- `LocationViewModel`: `occupancyHistory: Map<String, List<Float>>`, appended in
  `refreshOlcrtcSlots()`, capped at 16, never persisted.
- Wire `MEASURE` to `onRefreshClick(listOf(id))`.
- Wire `PkPlanBar` under the group header where both quota sides parse.

## Step 5 — settings screen, sheets, disclosure, camera

- `ApplicationSettingsSheet`: swap `ModalBottomSheet` for a full-bleed `Surface`
  with a back arrow. Signature unchanged, so no call site moves.
- Sections into `PkSettingsCard` / `PkToggleRow` / `PkLinkRow`.
- `AddConfigurationSheet`, `LogsSheet`, `ApplicationUpdateOfferSheet` onto
  `PkBottomSheet`.
- `VpnDisclosureScreen`: `Dialog` → `PkBottomSheet` with bullets and a lime CTA.
  `DISCLOSURE_BODY` is not edited. Still undismissable by back or outside tap.
- New `CameraRationaleSheet`, shown before `onScanQrRequested()` where
  `canScanQr`.

## Step 6 — onboarding

- `LocationBundleV4.onboardingSeenAt`, datasource + repository methods, a
  `HomeScreenViewModel` flow, mirroring the disclosure trio.
- `OnboardingScreen` with the three steps and the 8-segment ring.
- `REPLAY FIRST RUN` in settings passes null.

## Step 7 — two-pane at ≥720dp

`BoxWithConstraints` in `HomeScreen`; a `RoomDetailPane` for the right side.
Below the threshold nothing changes.

## Step 8 — verification and push

- Scratch `jvmTest` rendering every state at 402×874, 412×892 and 1024×1366;
  read the PNGs, fix what does not fit, then delete the scratch test.
- Full `:sharedUI:jvmTest` + `:desktopApp:compileKotlin`.
- Grep `commonMain/**/ui/` for platform names in user-visible strings and for the
  word "subscription" — the rule from the 4.3 work.
- Push to `proofkit`, poll `pr-checks` via the GitHub API.
- Hand over to the user for an Xcode build on their Mac.

## Execution notes

What the repo turned out to need that the plan did not say.

**Android keeps its own settings screen.** `AndroidAppSettingsSheets.kt` is 1400
lines with private copies of the header, the navigation row and the switch —
split tunnelling, the installed-app list and the connection modes exist only
there. Android's *home* screen is the shared one and needed nothing extra, but
the settings screen took the same three moves separately (step 5b). It cannot be
compiled on this box; CI's `assembleDebug` is its first real check.

**`HomeScreenContent` was split out of `HomeScreen`.** Not in the plan, and it
paid for itself twice: it is what lets the layout be rendered from a test with
fabricated data, and it is the one body the two-pane layout rearranges rather
than a second copy to keep in step.

**`PkSheetSurface` was split out of `PkBottomSheet`** for the same reason —
`ModalBottomSheet` puts itself in a platform window an `ImageComposeScene` never
captures, so every sheet was a layout nobody could look at off a device.

**The screenshots caught three things reading the code had not:** the whole screen
rendered white (`pkScreenBackground` drew a grid and a glow over a container
colour a transparent scaffold no longer supplies), the room card ellipsised
"United States" to nine characters, and with the title finally taking the row's
weight the transport tag ran into the seat count as "SEI7 free".

**`onCopyConfigRequested` was already dead** — declared on `HomeScreen` and never
read, on `main`. Removed from all four files rather than left dangling.

## Still open

- The Android settings screen is unverified beyond CI's compile — nobody has
  looked at it running.
- The plan bar is not repeated in the two-pane detail pane.
- The disclosure well scrolls with no fade at its edge, so on a short screen
  there is no cue that there is more below.
