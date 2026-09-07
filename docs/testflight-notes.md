# TestFlight — What to Test

Paste the section below into App Store Connect → TestFlight → What to Test.
Kept under TestFlight's 4000-character limit. English, to match the app.

---

This build is about connecting on mobile networks: "no route to host", "no
such host", rooms on your own Jitsi, and the red message that could not be
closed. Nothing about the interface changed since the last build.

WHAT CHANGED

• Before dialing out on cellular, the app now checks that the network
  interface it is about to use can actually reach the internet. On some
  carriers it used to pick the interface the phone keeps for VoLTE, which has
  an address and no route, and every connection died with "no route to host".
• The app resolves the carrier's host names itself, over the same interface it
  dials from, instead of asking the phone. If the first resolver stays silent
  for two seconds it moves on to the next one. This is for networks that block
  a public DNS outright, and for carriers whose own DNS will not name a host —
  the "no such host" that hit rooms on a self-hosted Jitsi.
• Jitsi signalling goes the same protected way now. On iOS a reconnect from
  inside a running tunnel used to have nowhere to go but the tunnel itself, so
  a short hiccup became a full drop and a fresh session.
• A dial that finds no route is retried instead of ending the attempt.
• The red box under the status strip can be closed: tap it. Stop clears it too.
• Every attempt writes one line naming the interface it used into the log you
  can share. When a connect fails, that line is the first thing we read.

WHAT TO TEST

1. Cellular only. Wi-Fi OFF, join a room, a few times. If it fails: Diagnostics
   (the icon on the home screen), share the log — before relaunching the app.
2. A Megafon SIM in St. Petersburg if you have one. This build exists for it.
3. If you run your own olcRTC on a Jitsi: join it with Wi-Fi off. It used to
   fail with "no such host" unless the phone happened to have the name cached.
4. Stay connected for ten minutes with something streaming. Note any pause and
   whether the app reconnected on its own; it should not drop the session for a
   pause of a few seconds any more.
5. Handover: connect on Wi-Fi, turn Wi-Fi off while connected, then back on. It
   should come back on its own within a minute each time.
6. The red message: Airplane Mode on, try to join, then tap the message. It
   should go away. Again, and press stop instead.

KNOWN AND EXPECTED

• The text inside the red box is still technical. Closing it is fixed; making it
  readable is next.
• Background energy stays "High" in Xcode's report: a tunnel keeping a
  connection alive, not a bug.
• A card may say "KEY NO LONGER VALID · REFRESH THIS LIST". The refresh button on
  the list header fixes it.
• If your carrier runs a whitelist that blocks all public DNS, no build can
  resolve names for you; tell us which carrier and we will look at it.

Please report anything that looks wrong with a screenshot — and, for a
connection that did not come up or dropped, the shared log file.
