# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

The whole interface is new. The round power button is gone.

WHAT CHANGED

• The home screen is a board of rooms. Tap a card to pick one; the bar at the
  bottom names what it will join — "TAKE A SEAT IN GERMANY", "LEAVE GERMANY".
• Each olcRTC room shows its seats: how many there are, how many are taken, and
  which one is yours. A room that is full says so and cannot be tapped.
• While connected, the strip at the top draws live throughput. If bytes are
  moving, that line moves.
• The selected card says what the connection looks like from outside — "A
  TELEMOST MEDIA SESSION", "A TLS HANDSHAKE TO A REAL WEBSITE".
• Settings is a full screen behind the gear, not a sheet you flick away.
• On iPad the board and the selected room sit side by side.
• First run now explains rooms and seats in three steps. Settings → REPLAY
  FIRST RUN shows it again.
• Before the camera is used for a QR code, the app explains why it wants it.
• "Copy Full Config" is gone. It put every server address and every server-list
  URL on the clipboard in one tap.

WHAT TO TEST

1. Connect and disconnect a few times. The bottom bar should always name the
   room you actually picked.
2. Refresh a server list WHILE CONNECTED (the circling arrows on a list's
   header). You should stay on your server. It used to jump you to the first
   one in the list and re-dial.
3. Measure latency (the bolt) on a weak connection. It should read "no ping",
   never "offline" — and never anything bad for the room you are connected to.
4. Leave it connected for a while and watch the phone's temperature and
   battery. This build does far less drawing than the last one.
5. iPad, both orientations.
6. Scroll the board with two server lists imported; fold one away.

KNOWN AND EXPECTED

• Battery use in the background stays "High" in Xcode's energy report. That is
  the tunnel keeping a connection alive, not a bug — it is what any VPN costs.
• A card may say "KEY NO LONGER VALID · REFRESH THIS LIST". That means the
  provider retired that room's key; the refresh button on the list header fixes
  it. Before this build the card just went blank and nothing would connect.
• Latency is only measurable for olcRTC rooms. Other transports show "—".

Please report anything that looks wrong with a screenshot — the interface
changed everywhere, so nothing is too small to mention.
